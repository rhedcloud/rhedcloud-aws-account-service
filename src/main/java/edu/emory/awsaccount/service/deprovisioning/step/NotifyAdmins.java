package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.awsaccount.service.provider.ProviderException;
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
import java.util.ListIterator;
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

        // Get the accountId and accountName from a previous step.
        String accountId = getStepPropertyValue("DELETE_ACCOUNT_METADATA", "accountId");
        String accountName = getStepPropertyValue("DELETE_ACCOUNT_METADATA", "accountName");

        if (accountId == null) {
            accountId = "not available";
        }
        if (accountName == null) {
            accountName = "not available";
        }

        List<String> publicIdsToNotify = new ArrayList<>();
        logger.info(LOGTAG + "Getting the list of public ids to notify");
        String countStr;
        Integer count;

        // Get all the deleted user admins.
        countStr = getStepPropertyValue("DELETE_ADMINS_FROM_ADMIN_ROLE", "deletedUserAdminIdentityDnCount");
        if (countStr == null || countStr.equalsIgnoreCase("not available")) {
            logger.info("deletedUserAdminIdentityDnCount is 'not available' or null, setting its value to 0.");
            countStr = "0";
        }
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " admin public ids");
        for (int index = 0; index < count; index++) {
            String dn = getStepPropertyValue("DELETE_ADMINS_FROM_ADMIN_ROLE", "deletedUserAdminIdentityDn" + index);
            String publicId = parseDnForUserId(dn);
            publicIdsToNotify.add(publicId);
        }

        // Get all the deleted auditors
        countStr = getStepPropertyValue("DELETE_AUDITORS_FROM_AUDITOR_ROLE", "deletedUserAuditorIdentityDnCount");
        if (countStr == null || countStr.equalsIgnoreCase("not available")) {
            logger.info("deletedUserAuditorIdentityDnCount is 'not available' or null, setting its value to 0.");
            countStr = "0";
        }
        logger.info(LOGTAG + "countStr is: " + countStr);
        count = Integer.valueOf(countStr);
        logger.info(LOGTAG + "Loading " + count + " auditor public ids");
        for (int index = 0; index < count; index++) {
            String dn = getStepPropertyValue("DELETE_AUDITORS_FROM_AUDITOR_ROLE", "deletedUserAuditorIdentityDn" + index);
            String publicId = parseDnForUserId(dn);
            publicIdsToNotify.add(publicId);
        }

        // Get a list of central administrators
        try {
            List<String> centralAdministratorList = getAccountDeprovisioningProvider()
                .getCentralAdministrators();
            addResultProperty("centralAdministratorCount",
                Integer.toString(centralAdministratorList.size()));
            ListIterator<String> li = centralAdministratorList.listIterator();
            while (li.hasNext()) {
                String id = (String)li.next();
                publicIdsToNotify.add(id);
            }
        }
        catch (ProviderException pe) {
            String errMsg = "An error occurred retrieving a list of central administrators. The exception " +
                "is: " + pe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, pe);
        }

        logger.info(LOGTAG + "Sending notifications to " + publicIdsToNotify.size() + " public ids.");
        addResultProperty("totalUsersToNotify", Integer.toString(publicIdsToNotify.size()));

        for (int index = 0; index < publicIdsToNotify.size(); index++) {
            String publicId = publicIdsToNotify.get(index);
            this.sendNotification(publicId, accountId, accountName);
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private void sendNotification(String userId, String accountId, String accountName) throws StepException {
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
            notification.setUserId(userId);
            notification.setType(this.notificationType);
            notification.setPriority(this.notificationPriority);
            notification.setSubject(this.notificationSubject);
            String notificationBody = this.getNotificationBody(accountId, accountName);
            notification.setText(notificationBody);
            notification.setRead("false");
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
            long startTime = System.currentTimeMillis();
            notification.create(requestService);
            long time = System.currentTimeMillis() - startTime;
            this.logger.info(LOGTAG + "Sent notification to user " + userId +
                " in " + time + " ms.");
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

    private String getNotificationBody(String accountId, String accountName) {
        String notificationBody = this.notificationTemplate;
        notificationBody = notificationBody.replace("ACCOUNT_NUMBER", accountId);
        notificationBody = notificationBody.replace("ACCOUNT_NAME", accountName);
        return notificationBody;
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

    String parseDnForUserId(String dn) {
        String LOGTAG = "[NotifyAdmins.parseDnForUserId] ";
        logger.info(LOGTAG + "User dn is: " + dn);
        String[] elements = dn.split(",");
        String userId = elements[0];
        logger.info(LOGTAG + "UserId is: " + userId);
        return userId;
    }

}
