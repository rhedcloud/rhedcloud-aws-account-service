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
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.*;

public class DeleteAdminsFromAdminRole extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteAdminsFromAdminRole";
    private ProducerPool idmServiceProducerPool;
    private String adminRoleDnTemplate;
    private String identityDnTemplate;

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

        String identityDnTemplate = getProperties().getProperty("identityDnTemplate");
        setIdentityDnTemplate(identityDnTemplate);
        logger.info(LOGTAG + "identityDnTemplate is: " + identityDnTemplate);

    }

    private void setIdentityDnTemplate(String template) throws StepException {
        if (template == null) {
            throw new StepException("identityDnTemplate cannot be null");
        }

        this.identityDnTemplate = template;
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

        String accountId = getStepPropertyValue("AUTHORIZE_REQUESTOR", "accountId");
        logger.info(LOGTAG + "The accountId is: " + accountId);

        String adminRoleDnTemplate = getStepPropertyValue("AUTHORIZE_REQUESTOR", "adminRoleDnTemplate");
        setAdminRoleDnTemplate(adminRoleDnTemplate);
        logger.info(LOGTAG + "adminRoleDnTemplate is: " + adminRoleDnTemplate);

        String adminRoleDn = this.getAdminRoleDn(accountId);
        List<String> adminIds = getAccountIdsAssignedToRole(adminRoleDn);

        // only attempt to remove accounts if there are accounts to be removed
        if (adminIds.size() > 0) {
            logger.info(LOGTAG + "Removing " + adminIds.size() + " admin(s) from admin role");
            for (int index = 0; index < adminIds.size(); index++) {
                this.deleteAdminFromRole(adminIds.get(index), adminRoleDnTemplate);
            }
        } else {
            logger.info(LOGTAG + "No admin accounts to be removed");
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private void setAdminRoleDnTemplate(String adminRoleDnTemplate) {
        this.adminRoleDnTemplate = adminRoleDnTemplate;
    }

    private String getAdminRoleDn(String accountId) {
        return this.adminRoleDnTemplate.replace("ACCOUNT_NUMBER", accountId);
    }

    private void deleteAdminFromRole(String accountId, String adminRoleDnTemplate) throws StepException {
        String LOGTAG = this.createLogTag("deleteAdminFromRole");

        String adminRoleDn = getAdminRoleDn(accountId);
        logger.info(LOGTAG + "Deleting admin role: " + adminRoleDn);

        RoleAssignment roleRevokation = null;
        RoleAssignmentQuerySpecification roleAssignmentQuerySpecification = null;

        try {
            roleRevokation = (RoleAssignment) getAppConfig().getObjectByType(RoleAssignment.class.getName());
            roleAssignmentQuerySpecification = (RoleAssignmentQuerySpecification) getAppConfig().getObjectByType(RoleAssignmentQuerySpecification.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        String identityDn = getIdentityDN(accountId);
        logger.info(LOGTAG + "Preparing to revoke admin for identityDn: " + identityDn);

        try {
            roleRevokation.setRoleAssignmentActionType("revoke");
            roleRevokation.setRoleAssignmentType("USER_TO_ROLE");
            roleRevokation.setIdentityDN(identityDn);
            roleRevokation.setRoleDN(adminRoleDn);
            roleRevokation.setReason("Account deprovisioning");
            logger.info(LOGTAG + "Role revokation XML is: " + roleRevokation.toXmlString());
        } catch (EnterpriseFieldException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } catch (XmlEnterpriseObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        RequestService requestService = null;

        try {
            logger.info(LOGTAG + "Getting request service");
            requestService = (RequestService) this.idmServiceProducerPool.getExclusiveProducer();
        } catch (JMSException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            logger.info(LOGTAG + "Deleting admin role: " + adminRoleDn);
            roleRevokation.delete("Delete", requestService);
        } catch (EnterpriseObjectDeleteException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        logger.info(LOGTAG + "Role successfully deleted");
    }

    private String getIdentityDN(String accountId) {
        return this.identityDnTemplate.replace("USER_ID", accountId);
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
            Set<String> uniqueIds = new HashSet<>();
            for (int index = 0; index < roleAssignments.size(); index++) {
                RoleAssignment role = roleAssignments.get(index);
                uniqueIds.add(role.getRoleDN());
            }
            result.addAll(uniqueIds);
            logger.info(LOGTAG + "Number of unique account ids returned: " + result.size());
            for (int index = 0; index < result.size(); index++) {
                logger.info(LOGTAG + "Account ID[" + index + "] is: " + result.get(index));
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
