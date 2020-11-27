/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.objects.resources.v1_0.Annotation;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
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

public class CustomRoleNotification extends AbstractStep implements Step {
    private ProducerPool awsAccountServiceProducerPool;
    private String notificationTemplate;
    private String notificationType;
    private String notificationPriority;
    private String notificationSubject;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[CustomRoleNotification.init] ";

        // This step needs to send messages to the AWS account service to send notifications.
        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Getting custom step properties...");

        setNotificationSubject(getMandatoryStringProperty(LOGTAG, "notificationSubject", false));
        setNotificationTemplate(getMandatoryStringProperty(LOGTAG, "notificationTemplate", false));
        setNotificationType(getMandatoryStringProperty(LOGTAG, "notificationType", false));
        setNotificationPriority(getMandatoryStringProperty(LOGTAG, "notificationPriority", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CustomRoleNotification.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleDeprovisioningRequisition requisition = getRoleDeprovisioning().getRoleDeprovisioningRequisition();
        String accountId = requisition.getAccountId();
        String roleName = requisition.getRoleName();
        String roleDeprovisioningId = getRoleDeprovisioning().getRoleDeprovisioningId();

        // Get a configured account notification object from AppConfig.
        AccountNotification aNotification;
        try {
            aNotification = (AccountNotification)getAppConfig().getObjectByType(AccountNotification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            Datetime createDatetime = new Datetime("Create", System.currentTimeMillis());

            aNotification.setAccountId(accountId);
            aNotification.setType(getNotificationType());
            aNotification.setPriority(getNotificationPriority());
            aNotification.setSubject(getNotificationSubject());
            aNotification.setText(getNotificationBody(accountId, roleName));
            aNotification.setReferenceId(roleDeprovisioningId);
            aNotification.setCreateUser("AwsAccountService");
            aNotification.setCreateDatetime(createDatetime);

            Annotation annotation = aNotification.newAnnotation();
            annotation.setText("AwsAccountService Custom Role Deprovisioning");
            annotation.setCreateUser("AwsAccountService");
            annotation.setCreateDatetime(createDatetime);
            aNotification.addAnnotation(annotation);

            logger.info(LOGTAG + "AccountNotification to create is: " + aNotification.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the AccountNotification. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the AccountNotification to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            aNotification.create(rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "Created AccountNotification in " + elapsedTime + " ms.");

            addResultProperty("sentNotification", Boolean.toString(true));
        }
        catch (EnterpriseObjectCreateException e) {
            String errMsg = "An error occurred creating the AccountNotification. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
        }

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CustomRoleNotification.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CustomRoleNotification.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_FAILURE);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_FAILURE);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        super.rollback();
        String LOGTAG = getStepTag() + "[CustomRoleNotification.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private String getNotificationBody(String accountId, String roleName) {
        return notificationTemplate
                .replace("ACCOUNT_NUMBER", accountId)
                .replace("CUSTOM_ROLE_NAME", roleName);
    }

    public ProducerPool getAwsAccountServiceProducerPool() { return awsAccountServiceProducerPool; }
    public void setAwsAccountServiceProducerPool(ProducerPool v) { this.awsAccountServiceProducerPool = v; }
    public String getNotificationTemplate() { return notificationTemplate; }
    public void setNotificationTemplate(String v) { this.notificationTemplate = v; }
    public String getNotificationType() { return notificationType; }
    public void setNotificationType(String v) { this.notificationType = v; }
    public String getNotificationPriority() { return notificationPriority; }
    public void setNotificationPriority(String v) { this.notificationPriority = v; }
    public String getNotificationSubject() { return notificationSubject; }
    public void setNotificationSubject(String v) { this.notificationSubject = v; }
}
