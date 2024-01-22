/*
 * Copyright 2010-2020 Alfresco Software, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.activiti.engine.impl.bpmn.behavior;

import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.naming.NamingException;

import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.activiti.engine.ActivitiException;
import org.activiti.engine.ActivitiIllegalArgumentException;
import org.activiti.engine.cfg.MailServerInfo;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.Expression;
import org.activiti.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.activiti.engine.impl.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;


/**



 */
public class MailActivityBehavior extends AbstractBpmnActivityBehavior {

  private static final long serialVersionUID = 1L;

  private static final Logger LOG = LoggerFactory.getLogger(MailActivityBehavior.class);

  private static final Class<?>[] ALLOWED_ATT_TYPES = new Class<?>[] { File.class, File[].class, String.class, String[].class, DataSource.class, DataSource[].class };

  protected Expression to;
  protected Expression from;
  protected Expression cc;
  protected Expression bcc;
  protected Expression subject;
  protected Expression text;
  protected Expression textVar;
  protected Expression html;
  protected Expression htmlVar;
  protected Expression charset;
  protected Expression ignoreException;
  protected Expression exceptionVariableName;
  protected Expression attachments;

  @Override
  public void execute(DelegateExecution execution) {

    boolean doIgnoreException = Boolean.parseBoolean(getStringFromField(ignoreException, execution));
    String exceptionVariable = getStringFromField(exceptionVariableName, execution);
    try {
      String toStr = getStringFromField(to, execution);
      String fromStr = getStringFromField(from, execution);
      String ccStr = getStringFromField(cc, execution);
      String bccStr = getStringFromField(bcc, execution);
      String subjectStr = getStringFromField(subject, execution);
      String textStr = textVar == null ? getStringFromField(text, execution) : getStringFromField(getExpression(execution, textVar), execution);
      String htmlStr = htmlVar == null ? getStringFromField(html, execution) : getStringFromField(getExpression(execution, htmlVar), execution);
      String charSetStr = getStringFromField(charset, execution);
      List<File> files = new LinkedList<File>();
      List<DataSource> dataSources = new LinkedList<DataSource>();
      getFilesFromFields(attachments, execution, files, dataSources);

      JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
      MimeMessage mimeMessage = mailSender.createMimeMessage();
      MimeMessageHelper msgHelper = new MimeMessageHelper(mimeMessage, "UTF-8");


      setCharset(mailSender, charSetStr);
      addTo(msgHelper, toStr);
      setFrom(msgHelper, fromStr, execution.getTenantId());
      addCc(msgHelper, ccStr);
      addBcc(msgHelper, bccStr);
      setSubject(msgHelper, subjectStr);
      addMailContent(msgHelper, textStr, htmlStr);
      attach(msgHelper, files, dataSources);

      setMailServerProperties(mailSender, execution.getTenantId());

      mailSender.send(mimeMessage);

    } catch (ActivitiException e) {
      handleException(execution, e.getMessage(), e, doIgnoreException, exceptionVariable);
    } catch (MessagingException e) {
      handleException(execution, "Could not send e-mail in execution " + execution.getId(), e, doIgnoreException, exceptionVariable);
    }

    leave(execution);
  }

  private boolean attachmentsExist(List<File> files, List<DataSource> dataSources) {
    return !((files == null || files.isEmpty()) && (dataSources == null || dataSources.isEmpty()));
  }

  protected void addMailContent(MimeMessageHelper msgHelper, String text, String html) {
      try {
        if (html != null) {
            msgHelper.setText(text, html);
        } else if (text != null) {
            msgHelper.setText(text);
        } else {
          throw new ActivitiIllegalArgumentException("'html' or 'text' is required to be defined when using the mail activity");
        }
      } catch (MessagingException e) {
          throw new RuntimeException(e);
      }
  }
  protected void addTo(MimeMessageHelper msgHelper, String to) {
    String[] tos = splitAndTrim(to);
    if (tos != null) {
      for (String t : tos) {
        try {
            msgHelper.setTo(t);
        } catch (MessagingException e) {
          throw new ActivitiException("Could not add " + t + " as recipient", e);
        }
      }
    } else {
      throw new ActivitiException("No recipient could be found for sending email");
    }
  }

  protected void setFrom(MimeMessageHelper msgHelper, String from, String tenantId) {
    String fromAddress = null;

    if (from != null) {
      fromAddress = from;
    } else { // use default configured from address in process engine config
      if (tenantId != null && tenantId.length() > 0) {
        Map<String, MailServerInfo> mailServers = Context.getProcessEngineConfiguration().getMailServers();
        if (mailServers != null && mailServers.containsKey(tenantId)) {
          MailServerInfo mailServerInfo = mailServers.get(tenantId);
          fromAddress = mailServerInfo.getMailServerDefaultFrom();
        }
      }

      if (fromAddress == null) {
        fromAddress = Context.getProcessEngineConfiguration().getMailServerDefaultFrom();
      }
    }

    try {
        msgHelper.setFrom(fromAddress);
    } catch (MessagingException e) {
      throw new ActivitiException("Could not set " + from + " as from address in email", e);
    }
  }

  protected void addCc(MimeMessageHelper msgHelper, String cc) {
    String[] ccs = splitAndTrim(cc);
    if (ccs != null) {
      for (String c : ccs) {
        try {
            msgHelper.addCc(c);
        } catch (MessagingException e) {
          throw new ActivitiException("Could not add " + c + " as cc recipient", e);
        }
      }
    }
  }

  protected void addBcc(MimeMessageHelper msgHelper, String bcc) {
    String[] bccs = splitAndTrim(bcc);
    if (bccs != null) {
      for (String b : bccs) {
        try {
            msgHelper.addBcc(b);
        } catch (MessagingException e) {
          throw new ActivitiException("Could not add " + b + " as bcc recipient", e);
        }
      }
    }
  }

  protected void attach(MimeMessageHelper msgHelper, List<File> files, List<DataSource> dataSources) throws MessagingException {
    if (!attachmentsExist(files, dataSources)) {
      return;
    }
    files.forEach(file -> {
        try {
            msgHelper.addAttachment(file.getName(), file);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    });

    dataSources.forEach(ds -> {
        try {
            msgHelper.addAttachment(ds.getName(), ds);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    });
  }

  protected void setSubject(MimeMessageHelper msgHelper, String subject) {
      try {
          msgHelper.setSubject(subject != null ? subject : "");
      } catch (MessagingException e) {
          throw new RuntimeException(e);
      }
  }

  protected void setMailServerProperties(JavaMailSenderImpl mailSender, String tenantId) {
    ProcessEngineConfigurationImpl processEngineConfiguration = Context.getProcessEngineConfiguration();

    boolean isMailServerSet = false;
    if (tenantId != null && tenantId.length() > 0) {
      if (processEngineConfiguration.getMailSessionJndi(tenantId) != null) {
        setEmailSession(mailSender, processEngineConfiguration.getMailSessionJndi(tenantId));
        isMailServerSet = true;

      } else if (processEngineConfiguration.getMailServer(tenantId) != null) {
        MailServerInfo mailServerInfo = processEngineConfiguration.getMailServer(tenantId);
        String host = mailServerInfo.getMailServerHost();
        if (host == null) {
          throw new ActivitiException("Could not send email: no SMTP host is configured for tenantId " + tenantId);
        }
        mailSender.setHost(host);

        mailSender.setPort(mailServerInfo.getMailServerPort());

        Properties props = new Properties();
        props.put("mail.smtp.starttls.enable",mailServerInfo.isMailServerUseTLS());
        props.put("mail.smtp.ssl.enable",mailServerInfo.isMailServerUseSSL());
        mailSender.setJavaMailProperties(props);

        String user = mailServerInfo.getMailServerUsername();
        String password = mailServerInfo.getMailServerPassword();
        if (user != null && password != null) {
          mailSender.setUsername(user);
          mailSender.setPassword(password);
        }

        isMailServerSet = true;
      }
    }

    if (!isMailServerSet) {
      String mailSessionJndi = processEngineConfiguration.getMailSessionJndi();
      if (mailSessionJndi != null) {
        setEmailSession(mailSender, mailSessionJndi);

      } else {
        String host = processEngineConfiguration.getMailServerHost();
        if (host == null) {
          throw new ActivitiException("Could not send email: no SMTP host is configured");
        }
        mailSender.setHost(host);

        int port = processEngineConfiguration.getMailServerPort();
        mailSender.setPort(port);

        Properties props = new Properties();
        props.put("mail.smtp.starttls.enable",processEngineConfiguration.getMailServerUseTLS());
        props.put("mail.smtp.ssl.enable",processEngineConfiguration.getMailServerUseSSL());
        mailSender.setJavaMailProperties(props);

        String user = processEngineConfiguration.getMailServerUsername();
        String password = processEngineConfiguration.getMailServerPassword();
        if (user != null && password != null) {
          mailSender.setUsername(user);
          mailSender.setPassword(password);
        }
      }
    }
  }

  protected void setEmailSession(JavaMailSenderImpl mailSender, String mailSessionJndi) {
    try {
//    TODO: find fix for below
      mailSender.setMailSessionFromJNDI(mailSessionJndi);
    } catch (NamingException e) {
      throw new ActivitiException("Could not send email: Incorrect JNDI configuration", e);
    }
  }

  protected void setCharset(JavaMailSenderImpl mailSender, String charSetStr) {
    if (charset != null) {
        mailSender.setDefaultEncoding(charSetStr);
    }
  }

  protected String[] splitAndTrim(String str) {
    if (str != null) {
      String[] splittedStrings = str.split(",");
      for (int i = 0; i < splittedStrings.length; i++) {
        splittedStrings[i] = splittedStrings[i].trim();
      }
      return splittedStrings;
    }
    return null;
  }

  protected String getStringFromField(Expression expression, DelegateExecution execution) {
    if (expression != null) {
      Object value = expression.getValue(execution);
      if (value != null) {
        return value.toString();
      }
    }
    return null;
  }

  private void getFilesFromFields(Expression expression, DelegateExecution execution, List<File> files, List<DataSource> dataSources) {
    Object value = checkAllowedTypes(expression, execution);
    if (value != null) {
      if (value instanceof File) {
        files.add((File) value);
      } else if (value instanceof String) {
        files.add(new File((String) value));
      } else if (value instanceof File[]) {
        Collections.addAll(files, (File[]) value);
      } else if (value instanceof String[]) {
        String[] paths = (String[]) value;
        for (String path : paths) {
          files.add(new File(path));
        }
      } else if (value instanceof DataSource) {
        dataSources.add((DataSource) value);
      } else if (value instanceof DataSource[]) {
        for (DataSource ds : (DataSource[]) value) {
          if (ds != null) {
            dataSources.add(ds);
          }
        }
      }
    }
    for (Iterator<File> it = files.iterator(); it.hasNext();) {
      File file = it.next();
      if (!fileExists(file)) {
        it.remove();
      }
    }
  }

  private Object checkAllowedTypes(Expression expression, DelegateExecution execution) {
    if (expression == null) {
      return null;
    }
    Object value = expression.getValue(execution);
    if (value == null) {
      return null;
    }
    for (Class<?> allowedType : ALLOWED_ATT_TYPES) {
      if (allowedType.isInstance(value)) {
        return value;
      }
    }
    throw new ActivitiException("Invalid attachment type: " + value.getClass());
  }

  protected boolean fileExists(File file) {
    return file != null && file.exists() && file.isFile() && file.canRead();
  }

  protected Expression getExpression(DelegateExecution execution, Expression var) {
    String variable = (String) execution.getVariable(var.getExpressionText());
    return Context.getProcessEngineConfiguration().getExpressionManager().createExpression(variable);
  }

  protected void handleException(DelegateExecution execution, String msg, Exception e, boolean doIgnoreException, String exceptionVariable) {
    if (doIgnoreException) {
      LOG.info("Ignoring email send error: " + msg, e);
      if (exceptionVariable != null && exceptionVariable.length() > 0) {
        execution.setVariable(exceptionVariable, msg);
      }
    } else {
      if (e instanceof ActivitiException) {
        throw (ActivitiException) e;
      } else {
        throw new ActivitiException(msg, e);
      }
    }
  }
}
