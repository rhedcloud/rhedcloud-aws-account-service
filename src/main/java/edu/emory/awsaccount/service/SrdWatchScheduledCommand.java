/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.jms.JMSException;
import javax.mail.internet.AddressException;

import org.apache.log4j.Logger;
import org.openeai.afa.ScheduledCommand;
import org.openeai.afa.ScheduledCommandException;
import org.openeai.afa.ScheduledCommandImpl;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.provider.ProviderException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.loggingutils.MailService;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloud;
import com.amazon.aws.moa.jmsobjects.security.v1_0.SecurityRiskDetection;
import com.amazon.aws.moa.jmsobjects.security.v1_0.SecurityRiskDetectionWatch;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.SecurityRiskDetectionQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudQuerySpecification;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;

import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileQuerySpecification;

/**
 * This command interrogate
 * 
 * @author George Wang (gwang28@emory.edu)
 * @version 1.0 - 4 July 2018
 * 
 */
public class SrdWatchScheduledCommand extends ScheduledCommandImpl implements ScheduledCommand {
    enum Assessment {
        EXPECTED, DEGRADED, UNACCEPTABLE
    };
    private String LOGTAG = "[SrdWatchScheduledCommand] ";
    protected static final Logger logger = Logger.getLogger(SrdWatchScheduledCommand.class);
    protected boolean m_verbose = false;
    protected static int requestTimeoutIntervalMilli = -1;
    protected ProducerPool awsAccountServiceRequestProducerPool;
    private int scheduleInterval;
    private int degradedBoundary;
    private int unacceptableBoundary;
    private boolean generateIncident;
    private boolean restartService;

    protected ProducerPool serviceNowServiceRequestProducerPool;
    protected String incidentRequisitionCallerId = "webservice_admin_integration";

    public SrdWatchScheduledCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
        logger.info(LOGTAG + " Initializing ...");
        PropertyConfig pConfig = new PropertyConfig();
        try {
            awsAccountServiceRequestProducerPool = (ProducerPool) getAppConfig().getObject("AwsAccountServiceRequest");
            serviceNowServiceRequestProducerPool = (ProducerPool) getAppConfig().getObject("ServiceNowServiceRequest");

            pConfig = (PropertyConfig) getAppConfig().getObject("GeneralProperties");
            Properties props = pConfig.getProperties();
            setProperties(props);
            scheduleInterval = Integer.parseInt(getProperties().getProperty("scheduleInterval"));
            degradedBoundary = Integer.parseInt(getProperties().getProperty("degradedBoundary"));
            unacceptableBoundary = Integer.parseInt(getProperties().getProperty("unacceptableBoundary"));
            generateIncident = Boolean.parseBoolean(getProperties().getProperty("generateIncident"));
            restartService = Boolean.parseBoolean(getProperties().getProperty("restartService"));
            logger.info(LOGTAG + "scheduleInterval=" + scheduleInterval);
            logger.info(LOGTAG + "degradedBoundary=" + degradedBoundary);
            logger.info(LOGTAG + "unacceptableBoundary=" + unacceptableBoundary);
            logger.info(LOGTAG + "generateIncident=" + generateIncident);
            logger.info(LOGTAG + "restartService=" + restartService);
            logger.info(LOGTAG + "scheduleInterval=" + scheduleInterval);

            // Get a mail service from AppConfig.
            MailService ms = null;
            try {
                ms = (MailService) getAppConfig().getObject("SrdWatchMailService");
                setMailService(ms);
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new ProviderException(errMsg, eoce);
            }
        } catch (Throwable t) {
            logger.error(LOGTAG, t);
            throw new InstantiationException(t.getMessage());
        }
        String verbose = getProperties().getProperty("verbose", "false");
        setVerbose(Boolean.getBoolean(verbose));
        logger.info(LOGTAG + "property verbose: " + getVerbose());

        logger.info(LOGTAG + " Initialization complete.");
    }

    protected MessageProducer getRequestServiceMessageProducer(ProducerPool producerPool) {
        MessageProducer producer = null;
        try {
            producer = producerPool.getExclusiveProducer();
            if (producer instanceof PointToPointProducer) {
                PointToPointProducer p2pp = (PointToPointProducer) producer;
                if (requestTimeoutIntervalMilli != -1) {
                    p2pp.setRequestTimeoutInterval(requestTimeoutIntervalMilli);
                    if (getVerbose())
                        logger.info(LOGTAG + "p2pProducer.setRequestTimeoutInterval=" + requestTimeoutIntervalMilli);
                }
            }
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service. The " + "exception is: " + jmse.getMessage();
            logger.fatal(LOGTAG + errMsg, jmse);
            throw new java.lang.UnsupportedOperationException(errMsg, jmse);
        }
        return producer;
    }
    protected Incident serviceNowIncidentGenerate(String shorDescription, String description)
            throws org.openeai.jms.consumer.commands.provider.ProviderException {
        List<Incident> generatedIncidents = null;
        try {
            Incident incident = (Incident) getAppConfig().getObjectByType(Incident.class.getName());
            IncidentRequisition incidentRequisition = (IncidentRequisition) getAppConfig()
                    .getObjectByType(IncidentRequisition.class.getName());
            incidentRequisition.setShortDescription(shorDescription);
            incidentRequisition.setDescription(description);
            incidentRequisition.setUrgency("3");
            incidentRequisition.setImpact("3");
            incidentRequisition.setBusinessService("Application Management");
            incidentRequisition.setCategory("Configuration");
            incidentRequisition.setSubCategory("Add");
            incidentRequisition.setRecordType("Service Request");
            incidentRequisition.setContactType("Integration");
            incidentRequisition.setCallerId(incidentRequisitionCallerId);
            incidentRequisition.setCmdbCi("Emory AWS Service");
            incidentRequisition.setAssignmentGroup("LITS: Messaging - Tier 3");
            logger.info(LOGTAG + "incident.generate:incidentRequisition=" + incidentRequisition.toXmlString());
            MessageProducer serviceNowProducer = getRequestServiceMessageProducer(serviceNowServiceRequestProducerPool);
            try {
                generatedIncidents = incident.generate(incidentRequisition, (RequestService) serviceNowProducer);
            } finally {
                serviceNowServiceRequestProducerPool.releaseProducer(serviceNowProducer);
            }
        } catch (Throwable e1) {
            logger.error(LOGTAG, e1);
            throw new org.openeai.jms.consumer.commands.provider.ProviderException("ServiceNowIncident.Generate error:" + e1.getMessage());
        }
        return generatedIncidents.get(0);
    }

    @Override
    public int execute() throws ScheduledCommandException {
        // TODO
        // purgeDbRecord?
        try {
            List<SecurityRiskDetection> srds = querySrds();
            float detectionPerMinute = (float) srds.size() / (float) scheduleInterval;
            SecurityRiskDetectionWatch srdWatch = (SecurityRiskDetectionWatch) getAppConfig()
                    .getObjectByType(SecurityRiskDetectionWatch.class.getName());
            srdWatch.setDetectionPerMinute(String.valueOf(detectionPerMinute));
            srdWatch.setDegradedBoundary(String.valueOf(degradedBoundary));
            srdWatch.setUnacceptableBoundary(String.valueOf(unacceptableBoundary));
            Assessment assessment = Assessment.EXPECTED;
            if (detectionPerMinute <= unacceptableBoundary)
                assessment = Assessment.UNACCEPTABLE;
            else if (detectionPerMinute <= degradedBoundary)
                assessment = Assessment.DEGRADED;
            srdWatch.setAssessment(assessment.toString());
            srdWatch.setCreateUser("SrdWatchCommand");
            Datetime createDatetime = srdWatch.newCreateDatetime();
            createDatetime.update(Calendar.getInstance());
            srdWatch.setCreateDatetime(createDatetime);
            MessageProducer messageProducer = getRequestServiceMessageProducer(awsAccountServiceRequestProducerPool);
            try {
                srdWatch.create((RequestService) messageProducer);
                logger.info(LOGTAG + "srdWatch record created=" + srdWatch.toXmlString());
            } finally {
                awsAccountServiceRequestProducerPool.releaseProducer(messageProducer);
            }

            if (assessment.equals(Assessment.UNACCEPTABLE)) {
                // TODO
                // serviceNowTicket
                sendEmail(detectionPerMinute);
                // TODO
                // restartService
            }

        } catch (Throwable e) {
            logger.error(LOGTAG, e);
        }
        return 0;
    }
    private void sendEmail(float dectionPerMinute) {
        MailService ms = getMailService();
        // try {
        // ms.setFromAddress(getEmailFromAddress());
        // ms.setRecipientList(dp.getEmail().getEmailAddress());
        // } catch (AddressException ae) {
        // String errMsg = "An error occurred setting addresses on " + "the
        // e-mail message. The exception is: " + ae.getMessage();
        // logger.error(LOGTAG + errMsg);
        // throw new ProviderException(errMsg, ae);
        // }
        ms.setSubject("AWS at Emory SrdWatch Notification");
        ms.setMessageBody("This is to notify that the Security Risk Detection Service has became unacceptable: Detection Per Minute="
                + dectionPerMinute + "from deploy env:" + getDeployEnv());
        long startTime = System.currentTimeMillis();
        logger.info(LOGTAG + "Sending e-mail message...");
        boolean sentMessage = ms.sendMessage();
        long time = System.currentTimeMillis() - startTime;
        if (sentMessage == true) {
            logger.info(LOGTAG + "Sent e-mail in " + time + " ms.");
        } else {
            String errMsg = "Failed to send e-mail.";
            logger.error(LOGTAG + errMsg);
        }
    }
    private static String getDeployEnv() {
        // docUriBase.dev=https://dev-config.app.emory.edu/
        // docUriBase.qa=https://qa-config.app.emory.edu/
        // docUriBase.stage=https://staging-config.app.emory.edu/
        // docUriBase.prod=https://config.app.emory.edu
        String docUriBase = System.getProperty("docUriBase");
        if (docUriBase != null && docUriBase.length() > 0) {
            int indexSlashSlash = docUriBase.indexOf("//");
            int indexConfig = docUriBase.indexOf("config");
            if (indexSlashSlash > 0 && indexConfig > 0) {
                String env = docUriBase.substring(indexSlashSlash + 2, indexConfig);
                if (env.length() == 0)
                    return "prod";
                if (env.endsWith("-"))
                    return env.substring(0, env.indexOf("-"));
            }
        }
        return "";
    }

    protected List<SecurityRiskDetection> querySrds()
            throws EnterpriseConfigurationObjectException, EnterpriseObjectQueryException, EnterpriseFieldException {
        SecurityRiskDetection srd = (SecurityRiskDetection) getAppConfig().getObjectByType(SecurityRiskDetection.class.getName());
        SecurityRiskDetectionQuerySpecification securityRiskDetectionQuerySpecification = (SecurityRiskDetectionQuerySpecification) getAppConfig()
                .getObjectByType(SecurityRiskDetectionQuerySpecification.class.getName());
        Datetime startCreateDatetime = securityRiskDetectionQuerySpecification.newEndCreateDatetime();
        Calendar calendar = Calendar.getInstance();
        long nowMillis = calendar.getTimeInMillis();
        long scheduleIntervalEarlier = nowMillis - scheduleInterval * 60 * 1000;
        calendar.setTimeInMillis(scheduleIntervalEarlier);
        securityRiskDetectionQuerySpecification.setStartCreateDatetime(startCreateDatetime);
        Datetime endCreateDatetime = securityRiskDetectionQuerySpecification.newEndCreateDatetime();
        endCreateDatetime.update(Calendar.getInstance());
        securityRiskDetectionQuerySpecification.setEndCreateDatetime(endCreateDatetime);
        MessageProducer messageProducer = getRequestServiceMessageProducer(awsAccountServiceRequestProducerPool);
        List<SecurityRiskDetection> virtualPrivateClouds = new ArrayList<>();
        try {
            virtualPrivateClouds = srd.query(securityRiskDetectionQuerySpecification, (RequestService) messageProducer);
        } finally {
            awsAccountServiceRequestProducerPool.releaseProducer(messageProducer);
        }
        return virtualPrivateClouds;
    }

    /**
     * @param boolean,
     *            the verbose parameter
     *            <P>
     *            Set a parameter to toggle verbose logging.
     */
    protected void setVerbose(boolean b) {
        m_verbose = b;
    }

    /**
     * @return boolean, the verbose parameter
     *         <P>
     *         Gets the value of the verbose logging parameter.
     */
    protected boolean getVerbose() {
        return m_verbose;
    }
}
