/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.activiti.engine.impl.bpmn.deployer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.activiti.bpmn.constants.BpmnXMLConstants;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.ExtensionElement;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.Process;
import org.activiti.bpmn.model.SubProcess;
import org.activiti.bpmn.model.UserTask;
import org.activiti.bpmn.model.ValuedDataObject;
import org.activiti.engine.DynamicBpmnConstants;
import org.activiti.engine.DynamicBpmnService;
import org.activiti.engine.delegate.event.ActivitiEventType;
import org.activiti.engine.delegate.event.impl.ActivitiEventBuilder;
import org.activiti.engine.impl.cfg.IdGenerator;
import org.activiti.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.interceptor.CommandContext;
import org.activiti.engine.impl.persistence.deploy.Deployer;
import org.activiti.engine.impl.persistence.entity.DeploymentEntity;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntityManager;
import org.activiti.engine.impl.persistence.entity.ResourceEntity;
import org.activiti.engine.impl.persistence.entity.ResourceEntityManager;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @author Joram Barrez
 * @author Tijs Rademakers
 */
public class BpmnDeployer implements Deployer {

  private static final Logger log = LoggerFactory.getLogger(BpmnDeployer.class);

  protected IdGenerator idGenerator;
  protected ParsedDeploymentBuilderFactory parsedDeploymentBuilderFactory;
  protected BpmnDeploymentHelper bpmnDeploymentHelper;
  protected CachingAndArtifactsManager cachingAndArtifactsManager;
  protected ProcessDefinitionDiagramHelper processDefinitionDiagramHelper;

  @Override
  public void deploy(DeploymentEntity deployment, Map<String, Object> deploymentSettings) {
    log.debug("Processing deployment {}", deployment.getName());

    // The ParsedDeployment represents the deployment, the process definitions, and the BPMN
    // resource, parse, and model associated with each process definition.
    // ParsedDeployment 用于描述与流程定义相关联的部署信息。BPMN资源和模型等等
    ParsedDeployment parsedDeployment = parsedDeploymentBuilderFactory
        .getBuilderForDeploymentAndSettings(deployment, deploymentSettings)
        .build();
    //校验是否有重复的 key
    bpmnDeploymentHelper.verifyProcessDefinitionsDoNotShareKeys(parsedDeployment.getAllProcessDefinitions());

    bpmnDeploymentHelper.copyDeploymentValuesToProcessDefinitions(
        parsedDeployment.getDeployment(), parsedDeployment.getAllProcessDefinitions());
    bpmnDeploymentHelper.setResourceNamesOnProcessDefinitions(parsedDeployment);

    //创建新的流程图
    createAndPersistNewDiagramsIfNeeded(parsedDeployment);
    //设置属性值
    setProcessDefinitionDiagramNames(parsedDeployment);

    if (deployment.isNew()) {
      Map<ProcessDefinitionEntity, ProcessDefinitionEntity> mapOfNewProcessDefinitionToPreviousVersion =
          getPreviousVersionsOfProcessDefinitions(parsedDeployment);
      //设置版本和 ID
      setProcessDefinitionVersionsAndIds(parsedDeployment, mapOfNewProcessDefinitionToPreviousVersion);
      //持久化流程定义到数据库并且添加有关的用户信息
      persistProcessDefinitionsAndAuthorizations(parsedDeployment);
      //更新TimerJob 和 EventSubscription
      updateTimersAndEvents(parsedDeployment, mapOfNewProcessDefinitionToPreviousVersion);
      //派发流程定义对象初始化事件
      dispatchProcessDefinitionEntityInitializedEvent(parsedDeployment);
    } else {
      makeProcessDefinitionsConsistentWithPersistedVersions(parsedDeployment);
    }
    // 将流程定义数据保存到缓存中
    cachingAndArtifactsManager.updateCachingAndArtifacts(parsedDeployment);

    for (ProcessDefinitionEntity processDefinition : parsedDeployment.getAllProcessDefinitions()) {
      BpmnModel bpmnModel = parsedDeployment.getBpmnModelForProcessDefinition(processDefinition);
      createLocalizationValues(processDefinition.getId(), bpmnModel.getProcessById(processDefinition.getKey()));
    }
  }

  /**
   * Creates new diagrams for process definitions if the deployment is new, the process definition in
   * question supports it, and the engine is configured to make new diagrams.
   *
   * When this method creates a new diagram, it also persists it via the ResourceEntityManager
   * and adds it to the resources of the deployment.
   */
  protected void createAndPersistNewDiagramsIfNeeded(ParsedDeployment parsedDeployment) {

    final ProcessEngineConfigurationImpl processEngineConfiguration = Context.getProcessEngineConfiguration();
    final DeploymentEntity deploymentEntity = parsedDeployment.getDeployment();

    final ResourceEntityManager resourceEntityManager = processEngineConfiguration.getResourceEntityManager();

    for (ProcessDefinitionEntity processDefinition : parsedDeployment.getAllProcessDefinitions()) {
      if (processDefinitionDiagramHelper.shouldCreateDiagram(processDefinition, deploymentEntity)) {
        ResourceEntity resource = processDefinitionDiagramHelper.createDiagramForProcessDefinition(
            processDefinition, parsedDeployment.getBpmnParseForProcessDefinition(processDefinition));
        if (resource != null) {
          resourceEntityManager.insert(resource, false);
          deploymentEntity.addResource(resource);  // now we'll find it if we look for the diagram name later.
        }
      }
    }
  }

  /**
   * Updates all the process definition entities to have the correct diagram resource name.  Must
   * be called after createAndPersistNewDiagramsAsNeeded to ensure that any newly-created diagrams
   * already have their resources attached to the deployment.
   */
  protected void setProcessDefinitionDiagramNames(ParsedDeployment parsedDeployment) {
    Map<String, ResourceEntity> resources = parsedDeployment.getDeployment().getResources();

    for (ProcessDefinitionEntity processDefinition : parsedDeployment.getAllProcessDefinitions()) {
      String diagramResourceName = ResourceNameUtil.getProcessDiagramResourceNameFromDeployment(processDefinition, resources);
      processDefinition.setDiagramResourceName(diagramResourceName);
    }
  }

  /**
   * Constructs a map from new ProcessDefinitionEntities to the previous version by key and tenant.
   * If no previous version exists, no map entry is created.
   */
  protected Map<ProcessDefinitionEntity, ProcessDefinitionEntity> getPreviousVersionsOfProcessDefinitions(
      ParsedDeployment parsedDeployment) {

    Map<ProcessDefinitionEntity, ProcessDefinitionEntity> result = new LinkedHashMap<ProcessDefinitionEntity, ProcessDefinitionEntity>();

    for (ProcessDefinitionEntity newDefinition : parsedDeployment.getAllProcessDefinitions()) {
      ProcessDefinitionEntity existingDefinition = bpmnDeploymentHelper.getMostRecentVersionOfProcessDefinition(newDefinition);

      if (existingDefinition != null) {
        result.put(newDefinition, existingDefinition);
      }
    }

    return result;
  }

  /**
   * Sets the version on each process definition entity, and the identifier.  If the map contains
   * an older version for a process definition, then the version is set to that older entity's
   * version plus one; otherwise it is set to 1.  Also dispatches an ENTITY_CREATED event.
   */
  protected void setProcessDefinitionVersionsAndIds(ParsedDeployment parsedDeployment,
      Map<ProcessDefinitionEntity, ProcessDefinitionEntity> mapNewToOldProcessDefinitions) {
    CommandContext commandContext = Context.getCommandContext();

    for (ProcessDefinitionEntity processDefinition : parsedDeployment.getAllProcessDefinitions()) {
      int version = 1;

      ProcessDefinitionEntity latest = mapNewToOldProcessDefinitions.get(processDefinition);
      if (latest != null) {
        version = latest.getVersion() + 1;
      }

      processDefinition.setVersion(version);
      processDefinition.setId(getIdForNewProcessDefinition(processDefinition));

      if (commandContext.getProcessEngineConfiguration().getEventDispatcher().isEnabled()) {
        commandContext.getProcessEngineConfiguration().getEventDispatcher().dispatchEvent(ActivitiEventBuilder.createEntityEvent(ActivitiEventType.ENTITY_CREATED, processDefinition));
      }
    }
  }

  /**
   * Saves each process definition.  It is assumed that the deployment is new, the definitions
   * have never been saved before, and that they have all their values properly set up.
   */
  protected void persistProcessDefinitionsAndAuthorizations(ParsedDeployment parsedDeployment) {
    CommandContext commandContext = Context.getCommandContext();
    ProcessDefinitionEntityManager processDefinitionManager = commandContext.getProcessDefinitionEntityManager();

    for (ProcessDefinitionEntity processDefinition : parsedDeployment.getAllProcessDefinitions()) {
      processDefinitionManager.insert(processDefinition, false);
      bpmnDeploymentHelper.addAuthorizationsForNewProcessDefinition(parsedDeployment.getProcessModelForProcessDefinition(processDefinition), processDefinition);
    }
  }

  protected void updateTimersAndEvents(ParsedDeployment parsedDeployment,
      Map<ProcessDefinitionEntity, ProcessDefinitionEntity> mapNewToOldProcessDefinitions) {

    for (ProcessDefinitionEntity processDefinition : parsedDeployment.getAllProcessDefinitions()) {
      bpmnDeploymentHelper.updateTimersAndEvents(processDefinition,
          mapNewToOldProcessDefinitions.get(processDefinition),
          parsedDeployment);
    }
  }

  protected void dispatchProcessDefinitionEntityInitializedEvent(ParsedDeployment parsedDeployment) {
    CommandContext commandContext = Context.getCommandContext();
    for (ProcessDefinitionEntity processDefinitionEntity : parsedDeployment.getAllProcessDefinitions()) {
      if (commandContext.getProcessEngineConfiguration().getEventDispatcher().isEnabled()) {
        commandContext.getProcessEngineConfiguration().getEventDispatcher().dispatchEvent(
            ActivitiEventBuilder.createEntityEvent(ActivitiEventType.ENTITY_INITIALIZED, processDefinitionEntity));
      }
    }
  }

  /**
   * Returns the ID to use for a new process definition; subclasses may override this to provide
   * their own identification scheme.
   *
   * Process definition ids NEED to be unique accross the whole engine!
   */
  protected String getIdForNewProcessDefinition(ProcessDefinitionEntity processDefinition) {
    String nextId = idGenerator.getNextId();

    String result = processDefinition.getKey() + ":" + processDefinition.getVersion() + ":" + nextId; // ACT-505
    // ACT-115: maximum id length is 64 characters
    if (result.length() > 64) {
      result = nextId;
    }

    return result;
  }

  /**
   * Loads the persisted version of each process definition and set values on the in-memory
   * version to be consistent.
   */
  protected void makeProcessDefinitionsConsistentWithPersistedVersions(ParsedDeployment parsedDeployment) {
    for (ProcessDefinitionEntity processDefinition : parsedDeployment.getAllProcessDefinitions()) {
      ProcessDefinitionEntity persistedProcessDefinition =
          bpmnDeploymentHelper.getPersistedInstanceOfProcessDefinition(processDefinition);

      if (persistedProcessDefinition != null) {
        processDefinition.setId(persistedProcessDefinition.getId());
        processDefinition.setVersion(persistedProcessDefinition.getVersion());
        processDefinition.setSuspensionState(persistedProcessDefinition.getSuspensionState());
      }
    }
  }

  protected void createLocalizationValues(String processDefinitionId, Process process) {
    if (process == null) return;

    CommandContext commandContext = Context.getCommandContext();
    DynamicBpmnService dynamicBpmnService = commandContext.getProcessEngineConfiguration().getDynamicBpmnService();
    ObjectNode infoNode = dynamicBpmnService.getProcessDefinitionInfo(processDefinitionId);

    boolean localizationValuesChanged = false;
    List<ExtensionElement> localizationElements = process.getExtensionElements().get("localization");
    if (localizationElements != null) {
      for (ExtensionElement localizationElement : localizationElements) {
        if (BpmnXMLConstants.ACTIVITI_EXTENSIONS_PREFIX.equals(localizationElement.getNamespacePrefix())) {
          String locale = localizationElement.getAttributeValue(null, "locale");
          String name = localizationElement.getAttributeValue(null, "name");
          String documentation = null;
          List<ExtensionElement> documentationElements = localizationElement.getChildElements().get("documentation");
          if (documentationElements != null) {
            for (ExtensionElement documentationElement : documentationElements) {
              documentation = StringUtils.trimToNull(documentationElement.getElementText());
              break;
            }
          }

          String processId = process.getId();
          if (isEqualToCurrentLocalizationValue(locale, processId, "name", name, infoNode) == false) {
            dynamicBpmnService.changeLocalizationName(locale, processId, name, infoNode);
            localizationValuesChanged = true;
          }

          if (documentation != null && isEqualToCurrentLocalizationValue(locale, processId, "description", documentation, infoNode) == false) {
            dynamicBpmnService.changeLocalizationDescription(locale, processId, documentation, infoNode);
            localizationValuesChanged = true;
          }

          break;
        }
      }
    }

    boolean isFlowElementLocalizationChanged = localizeFlowElements(process.getFlowElements(), infoNode);
    boolean isDataObjectLocalizationChanged = localizeDataObjectElements(process.getDataObjects(), infoNode);
    if (isFlowElementLocalizationChanged || isDataObjectLocalizationChanged) {
      localizationValuesChanged = true;
    }

    if (localizationValuesChanged) {
      dynamicBpmnService.saveProcessDefinitionInfo(processDefinitionId, infoNode);
    }
  }

  protected boolean localizeFlowElements(Collection<FlowElement> flowElements, ObjectNode infoNode) {
    boolean localizationValuesChanged = false;

    if (flowElements == null) return localizationValuesChanged;

    CommandContext commandContext = Context.getCommandContext();
    DynamicBpmnService dynamicBpmnService = commandContext.getProcessEngineConfiguration().getDynamicBpmnService();

    for (FlowElement flowElement : flowElements) {
      if (flowElement instanceof UserTask || flowElement instanceof SubProcess) {
        List<ExtensionElement> localizationElements = flowElement.getExtensionElements().get("localization");
        if (localizationElements != null) {
          for (ExtensionElement localizationElement : localizationElements) {
            if (BpmnXMLConstants.ACTIVITI_EXTENSIONS_PREFIX.equals(localizationElement.getNamespacePrefix())) {
              String locale = localizationElement.getAttributeValue(null, "locale");
              String name = localizationElement.getAttributeValue(null, "name");
              String documentation = null;
              List<ExtensionElement> documentationElements = localizationElement.getChildElements().get("documentation");
              if (documentationElements != null) {
                for (ExtensionElement documentationElement : documentationElements) {
                  documentation = StringUtils.trimToNull(documentationElement.getElementText());
                  break;
                }
              }

              String flowElementId = flowElement.getId();
              if (isEqualToCurrentLocalizationValue(locale, flowElementId, "name", name, infoNode) == false) {
                dynamicBpmnService.changeLocalizationName(locale, flowElementId, name, infoNode);
                localizationValuesChanged = true;
              }

              if (documentation != null && isEqualToCurrentLocalizationValue(locale, flowElementId, "description", documentation, infoNode) == false) {
                dynamicBpmnService.changeLocalizationDescription(locale, flowElementId, documentation, infoNode);
                localizationValuesChanged = true;
              }

              break;
            }
          }
        }

        if (flowElement instanceof SubProcess) {
          SubProcess subprocess = (SubProcess) flowElement;
          boolean isFlowElementLocalizationChanged = localizeFlowElements(subprocess.getFlowElements(), infoNode);
          boolean isDataObjectLocalizationChanged = localizeDataObjectElements(subprocess.getDataObjects(), infoNode);
          if (isFlowElementLocalizationChanged || isDataObjectLocalizationChanged) {
            localizationValuesChanged = true;
          }
        }
      }
    }

    return localizationValuesChanged;
  }

  protected boolean isEqualToCurrentLocalizationValue(String language, String id, String propertyName, String propertyValue, ObjectNode infoNode) {
    boolean isEqual = false;
    JsonNode localizationNode = infoNode.path("localization").path(language).path(id).path(propertyName);
    if (localizationNode.isMissingNode() == false && localizationNode.isNull() == false && localizationNode.asText().equals(propertyValue)) {
      isEqual = true;
    }
    return isEqual;
  }

  protected boolean localizeDataObjectElements(List<ValuedDataObject> dataObjects, ObjectNode infoNode) {
    boolean localizationValuesChanged = false;
    CommandContext commandContext = Context.getCommandContext();
    DynamicBpmnService dynamicBpmnService = commandContext.getProcessEngineConfiguration().getDynamicBpmnService();

    for(ValuedDataObject dataObject : dataObjects) {
      List<ExtensionElement> localizationElements = dataObject.getExtensionElements().get("localization");
      if (localizationElements != null) {
        for (ExtensionElement localizationElement : localizationElements) {
          if (BpmnXMLConstants.ACTIVITI_EXTENSIONS_PREFIX.equals(localizationElement.getNamespacePrefix())) {
            String locale = localizationElement.getAttributeValue(null, "locale");
            String name = localizationElement.getAttributeValue(null, "name");
            String documentation = null;

            List<ExtensionElement> documentationElements = localizationElement.getChildElements().get("documentation");
            if (documentationElements != null) {
              for (ExtensionElement documentationElement : documentationElements) {
                documentation = StringUtils.trimToNull(documentationElement.getElementText());
                break;
              }
            }

            if (name != null && isEqualToCurrentLocalizationValue(locale, dataObject.getId(), DynamicBpmnConstants.LOCALIZATION_NAME, name, infoNode) == false) {
              dynamicBpmnService.changeLocalizationName(locale, dataObject.getId(), name, infoNode);
              localizationValuesChanged = true;
            }

            if (documentation != null && isEqualToCurrentLocalizationValue(locale, dataObject.getId(),
                DynamicBpmnConstants.LOCALIZATION_DESCRIPTION, documentation, infoNode) == false) {

              dynamicBpmnService.changeLocalizationDescription(locale, dataObject.getId(), documentation, infoNode);
              localizationValuesChanged = true;
            }
          }
        }
      }
    }

    return localizationValuesChanged;
  }

  public IdGenerator getIdGenerator() {
    return idGenerator;
  }

  public void setIdGenerator(IdGenerator idGenerator) {
    this.idGenerator = idGenerator;
  }

  public ParsedDeploymentBuilderFactory getExParsedDeploymentBuilderFactory() {
    return parsedDeploymentBuilderFactory;
  }

  public void setParsedDeploymentBuilderFactory(ParsedDeploymentBuilderFactory parsedDeploymentBuilderFactory) {
    this.parsedDeploymentBuilderFactory = parsedDeploymentBuilderFactory;
  }

  public BpmnDeploymentHelper getBpmnDeploymentHelper() {
    return bpmnDeploymentHelper;
  }

  public void setBpmnDeploymentHelper(BpmnDeploymentHelper bpmnDeploymentHelper) {
    this.bpmnDeploymentHelper = bpmnDeploymentHelper;
  }

  public CachingAndArtifactsManager getCachingAndArtifcatsManager() {
    return cachingAndArtifactsManager;
  }

  public void setCachingAndArtifactsManager(CachingAndArtifactsManager manager) {
    this.cachingAndArtifactsManager = manager;
  }

  public ProcessDefinitionDiagramHelper getProcessDefinitionDiagramHelper() {
    return processDefinitionDiagramHelper;
  }

  public void setProcessDefinitionDiagramHelper(ProcessDefinitionDiagramHelper processDefinitionDiagramHelper) {
    this.processDefinitionDiagramHelper = processDefinitionDiagramHelper;
  }
}
