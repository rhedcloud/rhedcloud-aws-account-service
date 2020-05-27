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
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

public class DeleteAuditorsFromAuditorRole extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteAuditorsFromAuditorRole";
    private ProducerPool idmServiceProducerPool;
    private String auditorRoleDnTemplate;

    @Override
    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("init");
        logger.info(LOGTAG + "Begin step initialization.");

        super.init(deprovisioningId, props, aConfig, adp);

        ProducerPool producerPool = null;
        try {
            producerPool = (ProducerPool) getAppConfig().getObject("IdmServiceProducerPool");
            setIdmServiceProducerPool(producerPool);
        } catch (EnterpriseConfigurationObjectException error) {
            String message = "An error occurred retrieving an object from AppConfig. The exception is: " + error.getMessage();
            logger.fatal(LOGTAG + message);
            throw new StepException(message);
        }

        String auditorRoleDnTemplate = getProperties().getProperty("auditorRoleDnTemplate", null);
        setAuditorRoleDnTemplate(auditorRoleDnTemplate);
        logger.info(LOGTAG + "auditorRoleDnTemplate is: " + auditorRoleDnTemplate);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step initialization completed in " + time + "ms.");
    }

    private void setAuditorRoleDnTemplate(String auditorRoleDnTemplate) throws StepException {
        this.auditorRoleDnTemplate = auditorRoleDnTemplate;
        if (auditorRoleDnTemplate == null) {
            throw new StepException("auditorRoleDnTemplate cannot be null");
        }
    }

    private void setIdmServiceProducerPool(ProducerPool idmServiceProducerPool) {
        this.idmServiceProducerPool = idmServiceProducerPool;
    }

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
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

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */

        String auditorRoleDn = this.getAuditorRoleDn(accountId);
        logger.info(LOGTAG + "auditorRoleDn is: " + auditorRoleDn);
        List<RoleAssignment> roleAssignments = getRoleAssignments(auditorRoleDn);

        // only attempt to remove accounts if there are any to be removed
        if (!roleAssignments.isEmpty()) {
            for (int index = 0; index < roleAssignments.size(); index++) {
                RoleAssignment assignment = roleAssignments.get(index);
                String identityDn = assignment.getExplicitIdentityDNs().getDistinguishedName(0);
                logger.info(LOGTAG + "Removing " + identityDn + " from role " + auditorRoleDn);
                this.deleteAuditorFromRole(identityDn, auditorRoleDn);
            }
        } else {
            logger.info(LOGTAG + "No admin roles to be processed");
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private void deleteAuditorFromRole(String identityDn, String auditorRoleDn) {
        String LOGTAG = this.createLogTag("deleteAuditorFromRole");

        logger.info(LOGTAG + "Preparing to delete admin role");
        logger.info(LOGTAG + "identityDn is: " + identityDn);
        logger.info(LOGTAG + "auditorRoleDn is: " + auditorRoleDn);

        logger.info(LOGTAG + "Role successfully revoked");
    }

    private List<RoleAssignment> getRoleAssignments(String roleDn) throws StepException {
        String LOGTAG = this.createLogTag("getRoleAssignments");

        logger.info(LOGTAG + "Getting list of account ids assigned to auditor role: " + roleDn);

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
            logger.info(LOGTAG + "Query role assignments XML is: " + roleAssignmentQuerySpecification.toXmlString());
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

        List<RoleAssignment> result = null;

        try {
            result = roleAssignment.query(roleAssignmentQuerySpecification, requestService);
            logger.info(LOGTAG + "Number of role assignments returned: " + result.size());
            for (int index = 0; index < result.size(); index++) {
                logger.info(LOGTAG + "Assignment[" + index + "] is: " + result.get(index));
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

    private String getAuditorRoleDn(String accountId) {
        return this.auditorRoleDnTemplate.replace("ACCOUNT_NUMBER", accountId);
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
