package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class DeleteAdminsFromAdminRole extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteAdminsFromAdminRole";
    private ProducerPool idmServiceProducerPool;
    private String adminRoleDnTemplate;

    @Override
    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        super.init(deprovisioningId, props, aConfig, adp);

        String LOGTAG = createLogTag("init");

        ProducerPool producerPool = null;
        try {
            producerPool = (ProducerPool) getAppConfig().getObject("IdmServiceProducerPool");
            setIdmServiceProducerPool(producerPool);
        } catch (EnterpriseConfigurationObjectException error) {
            String message = "An error occurred retrieving an object from AppConfig. The exception is: " + error.getMessage();
            logger.fatal(LOGTAG + message);
            throw new StepException(message);
        }
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
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */

        String accountId = getStepPropertyValue("AUTHORIZE_REQUESTOR", "accountId");
        logger.info(LOGTAG + "The accountId is: " + accountId);

        String adminRoleDnTemplate = getStepPropertyValue("AUTHORIZE_REQUESTOR", "adminRoleDnTemplate");
        setAdminRoleDnTemplate(adminRoleDnTemplate);
        logger.info(LOGTAG + "adminRoleDnTemplate is: " + adminRoleDnTemplate);

        String adminRoleDn = this.getAdminRoleDn(accountId);
        List<String> adminIds = getAccountIdsAssignedToRole(adminRoleDn);

        logger.info(LOGTAG + "Removing " + adminIds.size() + " admin(s) from admin role");
        for (int index = 0; index < adminIds.size(); index++) {
            this.deleteAdminFromRole(adminIds.get(index), adminRoleDnTemplate);
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    private void setAdminRoleDnTemplate(String adminRoleDnTemplate) {
        this.adminRoleDnTemplate = adminRoleDnTemplate;
    }

    private String getAdminRoleDn(String accountId) {
        return this.adminRoleDnTemplate.replace("ACCOUNT_NUMBER", accountId);
    }

    private void deleteAdminFromRole(String accountId, String adminRoleDnTemplate) {
        String LOGTAG = createLogTag("deleteAdminFromRole");

        String adminRoleDn = getAdminRoleDn(accountId);
        logger.info(LOGTAG + "Deleting admin role: " + adminRoleDn);
    }

    private List<String> getAccountIdsAssignedToRole(String roleDn) throws StepException {
        String LOGTAG = this.createLogTag("getAccountIdsAssignedToRole");

        logger.info(LOGTAG + "Getting list of account ids assigned to role: " + roleDn);

        RoleAssignment roleAssignment = null;
        RoleAssignmentQuerySpecification roleAssignmentQuerySpecification = null;

        try {
            roleAssignment = (RoleAssignment) getAppConfig().getObjectByType(RoleAssignment.class.getName());
            roleAssignmentQuerySpecification = (RoleAssignmentQuerySpecification) getAppConfig().getObjectByType(RoleAssignmentQuerySpecification.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            roleAssignmentQuerySpecification.setRoleDN(roleDn);
            roleAssignmentQuerySpecification.setIdentityType("USER");
            roleAssignmentQuerySpecification.setDirectAssignOnly("true");
        } catch (EnterpriseFieldException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        RequestService requestService = null;

        try {
            requestService = (RequestService) this.idmServiceProducerPool.getExclusiveProducer();
        } catch (JMSException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        List<String> result = new ArrayList<>();

        try {
            List<RoleAssignment> roleAssignments = roleAssignment.query(roleAssignmentQuerySpecification, requestService);
            logger.info(LOGTAG + "Number of account ids returned: " + result.size());
            logger.info(LOGTAG + "totalRoleAssignments is:" + String.valueOf(result.size()));
            for (int index = 0; index < roleAssignments.size(); index++) {
                String propertyLabel = "roleAssignment" + String.valueOf(index);
                RoleAssignment role = roleAssignments.get(index);
                logger.info(LOGTAG + propertyLabel + " is: " + role.getRoleDN());
                result.add(role.getRoleDN());
            }
        } catch (EnterpriseObjectQueryException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } finally {
            this.idmServiceProducerPool.releaseProducer((PointToPointProducer) requestService);
        }

        long started = System.currentTimeMillis();
        long time = System.currentTimeMillis() - started;
        logger.info(LOGTAG + "Query for account id role assignments completed in " + time + "ms.");

        return result;
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

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
    }

    private void setIdmServiceProducerPool(ProducerPool pool) {
        this.idmServiceProducerPool = pool;
    }
}
