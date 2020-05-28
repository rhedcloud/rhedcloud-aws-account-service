package edu.emory.awsaccount.service.deprovisioning.step;


import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.Role;
import edu.emory.moa.objects.resources.v1_0.RoleRequisition;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.XmlEnterpriseObjectException;

import java.util.List;
import java.util.Properties;

public class DeleteIdmRoleAndResourcesForCentralAdminRole extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteIdmRoleAndResourcesForCentralAdminRole";
    private String resource3EntitlementDnTemplate;
    private String resource4EntitlementDn;
    private String resource5EntitlementDn;
    private String roleNameTemplate;
    private ProducerPool idmServiceProducerPool;

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

        String resource3EntitlementDnTemplate = getProperties().getProperty("resource3EntitlementDnTemplate");
        setResource3EntitlementDnTemplate(resource3EntitlementDnTemplate);
        logger.info(LOGTAG + "resource3EntitlementDnTemplate is: " + resource3EntitlementDnTemplate);

        String resource4EntitlementDn = getProperties().getProperty("resource4EntitlementDn");
        setResource4EntitlementDn(resource4EntitlementDn);
        logger.info(LOGTAG + "resource4EntitlementDn is: " + resource4EntitlementDn);

        String resource5EntitlementDn = getProperties().getProperty("resource5EntitlementDn");
        setResource5EntitlementDn(resource5EntitlementDn);
        logger.info(LOGTAG + "resource5EntitlementDn is: " + resource5EntitlementDn);

        String roleNameTemplate = getProperties().getProperty("roleNameTemplate", null);
        setRoleNameTemplate(roleNameTemplate);
        logger.info(LOGTAG + "roleNameTemplate is: " + roleNameTemplate);
    }

    private void setIdmServiceProducerPool(ProducerPool idmServiceProducerPool) {
        this.idmServiceProducerPool = idmServiceProducerPool;
    }

    private void setRoleNameTemplate(String template) throws StepException {
        if (template == null) throw new StepException("getProperty cannot be null");
        this.roleNameTemplate = template;
    }

    private void setResource5EntitlementDn(String template) throws StepException {
        if (template == null) throw new StepException("resource5EntitlementDn cannot be null");
        this.resource5EntitlementDn = template;
    }

    private void setResource4EntitlementDn(String template) throws StepException {
        if (template == null) throw new StepException("resource4EntitlementDn cannot be null");
        this.resource4EntitlementDn = template;
    }

    private void setResource3EntitlementDnTemplate(String template) throws StepException {
        if (template == null) throw new StepException("resource3EntitlementTemplate cannot be null");
        this.resource3EntitlementDnTemplate = template;
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

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */

        Role role = null;
        RoleRequisition requisition = null;

        try {
            role = (Role) getAppConfig().getObjectByType(Role.class.getName());
            requisition = (RoleRequisition) getAppConfig().getObjectByType(RoleRequisition.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            String roleName = getRoleName(accountId);
            logger.info(LOGTAG + "roleName is: " + roleName);
            requisition.setRoleName(roleName);
        } catch (EnterpriseFieldException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        try {
            logger.info(LOGTAG + "Requisition XML is: " + requisition.toXmlString());
        } catch (XmlEnterpriseObjectException error) {
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
