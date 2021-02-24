/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Annotation;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Notify administrators about the provisioning.
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 30 August 2018
 **/
public class NotifyAdmins extends AbstractStep implements Step {

    private ProducerPool m_awsAccountServiceProducerPool = null;
    private String m_notificationTemplateVpn;
    private String m_notificationTemplateTgw;

    public void init(String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {
        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[NotifyAdmins.init] ";

        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        setNotificationTemplateVpn(getProperties().getProperty("notificationTemplateVpn"));
        setNotificationTemplateTgw(getProperties().getProperty("notificationTemplateTgw"));
        logger.info(LOGTAG + "notificationTemplateVpn is: " + getNotificationTemplateVpn());
        logger.info(LOGTAG + "notificationTemplateTgw is: " + getNotificationTemplateTgw());

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[NotifyAdmins.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        // Return properties
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        // Get the VirtualPrivateCloudRequisition object.
        VirtualPrivateCloudProvisioning vpcp = getVirtualPrivateCloudProvisioning();
        VirtualPrivateCloudRequisition req = vpcp.getVirtualPrivateCloudRequisition();

        // Get the allocatedNewAccount property from the
        // GENERATE_NEW_ACCOUNT step.
        logger.info(LOGTAG + "Getting properties from preceding steps...");

        String accountId = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
        if (accountId.equals(PROPERTY_VALUE_NOT_APPLICABLE) || accountId.equals(PROPERTY_VALUE_NOT_AVAILABLE)) {
            accountId = req.getAccountId();
            if (accountId == null || accountId.equals("")) {
                String errMsg = "No account number for the notification can be found. Can't continue.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }
        String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");

        // Get a configured account notification object from AppConfig.
        AccountNotification aNotification = new AccountNotification();
        try {
            aNotification = (AccountNotification) getAppConfig().getObjectByType(aNotification.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }

        // Set the values of the account.
        try {
            aNotification.setAccountId(accountId);
            aNotification.setType("Provisioning");
            aNotification.setPriority("High");
            aNotification.setSubject("Successful Provisioning");
            aNotification.setText(getNotificationText(req, vpcConnectionMethod));
            aNotification.setReferenceId(vpcp.getProvisioningId());
            aNotification.setCreateUser(req.getAuthenticatedRequestorUserId());
            Datetime createDatetime = new Datetime("Create", System.currentTimeMillis());
            aNotification.setCreateDatetime(createDatetime);

            Annotation annotation = aNotification.newAnnotation();
            annotation.setText("AwsAccountService Provisioning");
            annotation.setCreateUser(req.getAuthenticatedRequestorUserId());
            annotation.setCreateDatetime(createDatetime);
            aNotification.addAnnotation(annotation);
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the query spec. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Log the state of the account.
        try {
            logger.info(LOGTAG + "AccountNotification to create is: " + aNotification.toXmlString());
        } catch (XmlEnterpriseObjectException xeoe) {
            String errMsg = "An error occurred serializing the query spec to XML. The exception is: " + xeoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, xeoe);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, jmse);
        }

        try {
            long createStartTime = System.currentTimeMillis();
            aNotification.create(rs);
            long createTime = System.currentTimeMillis() - createStartTime;
            logger.info(LOGTAG + "Created AccountNotification in " + createTime + " ms.");
            addResultProperty("sentNotification", "true");
        } catch (EnterpriseObjectCreateException eoce) {
            String errMsg = "An error occurred creating the object. The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoce);
        } finally {
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
        }

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[NotifyAdmins.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("sentNotification", "false");

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[NotifyAdmins.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        // Update the step.
        update(COMPLETED_STATUS, FAILURE_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();

        super.rollback();

        String LOGTAG = getStepTag() + "[NotifyAdmins.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        addResultProperty("adminNotificationRollback", "not applicable");

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void setAwsAccountServiceProducerPool(ProducerPool pool) {
        m_awsAccountServiceProducerPool = pool;
    }

    private ProducerPool getAwsAccountServiceProducerPool() {
        return m_awsAccountServiceProducerPool;
    }

    private void setNotificationTemplateVpn(String template) throws StepException {
        if (template == null) {
            String errMsg = "notificationTemplateVpn property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_notificationTemplateVpn = template;
    }

    private String getNotificationTemplateVpn() {
        return m_notificationTemplateVpn;
    }

    private void setNotificationTemplateTgw(String template) throws StepException {
        if (template == null) {
            String errMsg = "notificationTemplateTgw property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_notificationTemplateTgw = template;
    }

    private String getNotificationTemplateTgw() {
        return m_notificationTemplateTgw;
    }

    private String getNotificationText(VirtualPrivateCloudRequisition req, String vpcConnectionMethod) throws StepException {
        String text;
        if (vpcConnectionMethod.equals("VPN")) {
            text = getNotificationTemplateVpn();
        } else if (vpcConnectionMethod.equals("TGW")) {
            text = getNotificationTemplateTgw();
        } else {
            text = "";
        }
        text = text.replaceAll("\\s+", " ");

        String request;
        try {
            request = req.toXmlString();
        } catch (XmlEnterpriseObjectException xeoe) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + xeoe.getMessage();
            logger.error(getStepTag() + errMsg);
            throw new StepException(errMsg, xeoe);
        }
        text = text + "\n\nThe details of the request are:\n\n" + request;

        return text;
    }
}
