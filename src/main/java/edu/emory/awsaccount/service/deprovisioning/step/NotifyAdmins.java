package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

public class NotifyAdmins extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "NotifyAdmins";
    private ProducerPool awsAccountServiceProducerPool;
    private String notificationTemplate;
    private String notificationType;
    private String notificationPriority;
    private String notificationSubject;

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
    }

    @Override
    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        super.init(deprovisioningId, props, aConfig, adp);

        String LOGTAG = createLogTag("init");

        try {
            ProducerPool producerPool = (ProducerPool) getAppConfig()
                    .getObject("AwsAccountServiceProducerPool");
            this.setAwsAccountServiceProducerPool(producerPool);
        } catch (EnterpriseConfigurationObjectException error) {
            String message = "An error occurred retrieving an object from AppConfig. The exception is: " + error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }


        String notificationTemplate = getProperties().getProperty("notificationTemplate", null);
        this.setNotificationTemplate(notificationTemplate);
        this.logger.info(LOGTAG + "notificationTemplate is: " + notificationTemplate);

        String notificationType = getProperties().getProperty("notificationType", null);
        this.setNotificationType(notificationType);
        this.logger.info(LOGTAG + "notificationType is: " + notificationType);

        String notificationPriority = getProperties().getProperty("notificationPriority", null);
        this.setNoficationPriority(notificationPriority);
        this.logger.info(LOGTAG + "notificationPriority is: " + notificationPriority);

        String notificationSubject = getProperties().getProperty("notificationSubject", null);
        this.setNotificationSubject(notificationSubject);
        this.logger.info(LOGTAG + "notificationSubject is: " + notificationSubject);
    }

    private void setNotificationSubject(String notificationSubject) throws StepException {
        if (notificationSubject == null) throw new StepException("notificationSubject cannot be null");
        this.notificationSubject = notificationSubject;
    }

    private void setNoficationPriority(String notificationPriority) throws StepException {
        if (notificationPriority == null) throw new StepException("notificationPriority cannot be null");
        this.notificationPriority = notificationPriority;
    }

    private void setNotificationType(String notificationType) throws StepException {
        if (notificationType == null) throw new StepException("notificationType cannot be null");
        this.notificationType = notificationType;
    }

    private void setAwsAccountServiceProducerPool(ProducerPool producerPool) {
        this.awsAccountServiceProducerPool = producerPool;
    }

    private void setNotificationTemplate(String notificationTemplate) throws StepException {
        if (notificationTemplate == null) throw new StepException("notificationTemplate cannot be null");
        this.notificationTemplate = notificationTemplate;
    }

    @Override
    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("simulate");
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("isAuthorized", "true");

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("run");
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */

        List<String> publicIdsToNotify = new ArrayList<>();
        logger.info(LOGTAG + "Getting the list of public ids to notify");
        String countStr;
        Integer count;

        countStr = getStepPropertyValue("DELETE_ADMINS_FROM_ADMIN_ROLE", "deletedUserAdminIdentityDnCount");
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " admin public ids");
        for (int index = 0; index < count; index++) {
            String publicId = getStepPropertyValue("DELETE_ADMINS_FROM_ADMIN_ROLE", "deletedUserAdminIdentityDn" + index);
            publicIdsToNotify.add(publicId);
        }

        countStr = getStepPropertyValue("DELETE_ADMINS_FROM_ADMIN_ROLE", "deletedGroupAdminIdentityDnCount");
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " admin public ids");
        for (int index = 0; index < count; index++) {
            String publicId = getStepPropertyValue("DELETE_ADMINS_FROM_ADMIN_ROLE", "deletedUserAdminIdentityDn" + index);
            publicIdsToNotify.add(publicId);
        }

        countStr = getStepPropertyValue("DELETE_AUDITORS_FROM_AUDITOR_ROLE", "deletedUserAdminIdentityDnCount");
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " admin public ids");
        for (int index = 0; index < count; index++) {
            String publicId = getStepPropertyValue("DELETE_AUDITORS_FROM_AUDITOR_ROLE", "deletedUserAuditorIdentityDn" + index);
            publicIdsToNotify.add(publicId);
        }

        countStr = getStepPropertyValue("DELETE_AUDITORS_FROM_AUDITOR_ROLE", "deletedGroupAuditorIdentityDnCount");
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " admin public ids");
        for (int index = 0; index < count; index++) {
            String publicId = getStepPropertyValue("DELETE_AUDITORS_FROM_AUDITOR_ROLE", "deletedGroupAuditorIdentityDn" + index);
            publicIdsToNotify.add(publicId);
        }

        countStr = getStepPropertyValue("DELETE_CENTRAL_ADMINS_FROM_ADMIN_ROLE", "deletedCentralAdminsUserIdentityDnCount");
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " admin public ids");
        for (int index = 0; index < count; index++) {
            String publicId = getStepPropertyValue("DELETE_CENTRAL_ADMINS_FROM_ADMIN_ROLE", "deletedUserCentralAdminUserIdentityDn" + index);
            publicIdsToNotify.add(publicId);
        }

        countStr = getStepPropertyValue("DELETE_CENTRAL_ADMINS_FROM_ADMIN_ROLE", "deletedCentralAdminsGroupIdentityDnCount");
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " admin public ids");
        for (int index = 0; index < count; index++) {
            String publicId = getStepPropertyValue("DELETE_CENTRAL_ADMINS_FROM_ADMIN_ROLE", "deletedUserCentralAdminGroupIdentityDn" + index);
            publicIdsToNotify.add(publicId);
        }

        logger.info(LOGTAG + "Sending notifications to " + publicIdsToNotify + " public ids:");

        for (int index = 0; index < publicIdsToNotify.size(); index++) {
            String publicId = publicIdsToNotify.get(index);
            this.sendNotification(publicId);
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private void sendNotification(String publicId) throws StepException {
        String LOGTAG = createLogTag("sendNotification");

        UserNotification notification;

        try {
            notification = (UserNotification) getAppConfig().getObjectByType(UserNotification.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        this.logger.info(LOGTAG + "Creating the notification");

        try {
            notification.setAccountNotificationId(this.getAccountIdFromPublicId(publicId));
            notification.setType(this.notificationType);
            notification.setPriority(this.notificationPriority);
            notification.setSubject(this.notificationSubject);
            notification.setRead("false");
            String notificationBody = this.getNotificationBody(publicId);
            notification.setText(notificationBody);
            notification.setCreateUser("AwsAccountService");
            notification.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
        } catch (EnterpriseFieldException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        RequestService requestService;

        try {
            requestService = (RequestService) this.awsAccountServiceProducerPool.getExclusiveProducer();
        } catch (JMSException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            notification.create(requestService);
            this.logger.info(LOGTAG + "Notification sent to: " + publicId);
        } catch (EnterpriseObjectCreateException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } finally {
            this.awsAccountServiceProducerPool
                    .releaseProducer((MessageProducer) requestService);
        }
    }

    private String getAccountIdFromPublicId(String publicId) {
        StringTokenizer tokens1 = new StringTokenizer(publicId, ",");
        StringTokenizer tokens2 = new StringTokenizer(tokens1.nextToken(), "=");
        String result = null;
        while (tokens2.hasMoreElements()) result = tokens2.nextToken();

        return result;
    }

    private String getNotificationBody(String publicId) {
        return this.notificationTemplate.replace("USER_ID", publicId);
    }

    @Override
    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("fail");
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        update(COMPLETED_STATUS, FAILURE_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("rollback");

        logger.info(LOGTAG + "Rollback called, but this step has nothing to " +
                "roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }
}
