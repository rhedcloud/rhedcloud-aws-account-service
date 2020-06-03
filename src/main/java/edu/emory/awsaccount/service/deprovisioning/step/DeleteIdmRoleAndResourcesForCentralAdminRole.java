package edu.emory.awsaccount.service.deprovisioning.step;


import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.Role;
import edu.emory.moa.objects.resources.v1_0.RoleQuerySpecification;
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
import java.util.List;
import java.util.Properties;

public class DeleteIdmRoleAndResourcesForCentralAdminRole extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteIdmRoleAndResourcesForCentralAdminRole";
    private String roleNameTemplate;
    private ProducerPool idmServiceProducerPool;
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

        String identityDnTemplate = getProperties().getProperty("identityDnTemplate", null);
        setIdentityDnTemplate(identityDnTemplate);
        logger.info(LOGTAG + "identityDnTemplate is: " + identityDnTemplate);

        String roleNameTemplate = getProperties().getProperty("roleNameTemplate", null);
        setRoleNameTemplate(roleNameTemplate);
        logger.info(LOGTAG + "roleNameTemplate is: " + roleNameTemplate);
    }

    private void setIdentityDnTemplate(String template) throws StepException {
        if (template == null) throw new StepException("identityDnTemplate cannot be null");
        this.identityDnTemplate = template;
    }

    private void setIdmServiceProducerPool(ProducerPool idmServiceProducerPool) {
        this.idmServiceProducerPool = idmServiceProducerPool;
    }

    private void setRoleNameTemplate(String template) throws StepException {
        if (template == null) throw new StepException("getProperty cannot be null");
        this.roleNameTemplate = template;
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

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        logger.info(LOGTAG + "accountId is: " + accountId);
        addResultProperty("accountId", accountId);

        String roleName = getRoleName(accountId);
        logger.info(LOGTAG + "roleName is: " + roleName);
        addResultProperty("roleName", roleName);

        String identityDn = getIdentityDn(accountId);

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */

        try {
            List<Role> roles = getRolesForRoleName(roleName);

            if (!roles.isEmpty()) {
                for (int index = 0; index < roles.size(); index++) {
                    this.deleteRole(roles.get(index).getRoleDN(), roleName);
                }
            } else {
                logger.info(LOGTAG + "No central admin roles to delete");
            }
        } catch (StepException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private void deleteRole(String roleDN, String roleName) throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("deleteRole");
        logger.info(LOGTAG + "Deleting role.");
        logger.info(LOGTAG + "roleDN is: " + roleDN);
        logger.info(LOGTAG + "roleName is: " + roleName);

        Role role = null;

        try {
            role = (Role) getAppConfig().getObjectByType(Role.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            role.setRoleName(roleName);
            role.setRoleDN(roleDN);
        } catch (EnterpriseFieldException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        RequestService requestService = null;

        try {
            logger.info(LOGTAG + "Getting request service");
            requestService = (RequestService) this.idmServiceProducerPool.getExclusiveProducer();

            logger.info(LOGTAG + "Deleting IDM role");
            role.delete("Delete", requestService);
        } catch (JMSException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } catch (EnterpriseObjectDeleteException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } finally {
            this.idmServiceProducerPool.releaseProducer((PointToPointProducer) requestService);
        }

        logger.info("Role successfully deleted.");
    }

    private List<Role> getRolesForRoleName(String roleName) throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("getRolesForRoleName");
        logger.info(LOGTAG + "Begin getting list of roles.");
        logger.info(LOGTAG + "roleName is: " + roleName);

        Role role = null;
        RoleQuerySpecification roleQuerySpecification = null;

        try {
            role = (Role) getAppConfig().getObjectByType(Role.class.getName());
            roleQuerySpecification = (RoleQuerySpecification) getAppConfig().getObjectByType(RoleQuerySpecification.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            roleQuerySpecification.setRoleDN(roleName);
            logger.info(LOGTAG + "Query role assignments XML is: " + roleQuerySpecification.toXmlString());
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
            requestService = (RequestService) this.idmServiceProducerPool.getExclusiveProducer();
        } catch (JMSException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        List<Role> result = null;

        try {
            result = role.query(roleQuerySpecification, requestService);
            logger.info(LOGTAG + "Number of roles returned: " + result.size());
            for (int index = 0; index < result.size(); index++) {
                logger.info(LOGTAG + "Role[" + index + "] is: " + result.get(index));
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
        logger.info(LOGTAG + "Completed in " + time + "ms.");

        return result;
    }

    private String getIdentityDn(String accountId) {
        return this.identityDnTemplate.replace("USER_ID", accountId);
    }

    private String getRoleName(String accountId) {
        return this.roleNameTemplate.replace("ACCOUNT_NUMBER", accountId);
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
}
