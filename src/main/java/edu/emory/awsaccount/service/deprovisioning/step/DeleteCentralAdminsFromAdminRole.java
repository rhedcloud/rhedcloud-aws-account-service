package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.ExplicitIdentityDNs;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.RoleDNs;
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

public class DeleteCentralAdminsFromAdminRole extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteCentralAdminsFromAdminRole";
    private ProducerPool idmServiceProducerPool;
    private String centralAdminRoleDnTemplate;

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

        String centralAdminRoleDnTemplate = getProperties().getProperty("centralAdminRoleDnTemplate", null);
        logger.info(LOGTAG + "centralAdminRoleDnTemplate is: " + centralAdminRoleDnTemplate);
        setCentralAdminRoleDnTemplate(centralAdminRoleDnTemplate);
    }

    private void setCentralAdminRoleDnTemplate(String centralAdminRoleDnTemplate) throws StepException {
        String LOGTAG = createLogTag("centralAdminRoleDn");
        logger.info(LOGTAG + "centralAdminRoleDn is: " + centralAdminRoleDnTemplate);

        if (centralAdminRoleDnTemplate == null) {
            throw new StepException("centralAdminRoleDnTemplate cannot be null");
        }
        this.centralAdminRoleDnTemplate = centralAdminRoleDnTemplate;
    }

    private void setIdmServiceProducerPool(ProducerPool producerPool) {
        this.idmServiceProducerPool = producerPool;
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

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
    }

    @Override
    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("run");
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        addResultProperty("accountId", accountId);
        logger.info(LOGTAG + "accountId is: " + accountId);

        String centralAdminRoleDn = this.getCentralAdminRoleDn(accountId);
        addResultProperty("centralAdminRoleDn", centralAdminRoleDn);
        logger.info(LOGTAG + "centralAdminRoleDn is: " + centralAdminRoleDn);

        /* begin business logic */

        // get the list of central admins
        List<RoleAssignment> centralAdmins = this.getCentralAdmins(centralAdminRoleDn);
        logger.info(LOGTAG + "Found " + centralAdmins.size() + " central administrator(s)");

        // delete the central admins from the central admin role
        if (centralAdmins.size() > 0) {
            for (int index = 0; index < centralAdmins.size(); index++) {
                RoleAssignment assignment = centralAdmins.get(index);
                String identityDn = assignment.getExplicitIdentityDNs().getDistinguishedName(0);
                this.deleteCentralAdmin(centralAdminRoleDn, identityDn);
            }
        } else {
            logger.info(LOGTAG + " No admins to be removed");
        }

        addResultProperty("centralAdminsRemoved", String.valueOf(centralAdmins.size()));

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private String getCentralAdminRoleDn(String accountId) {
        return this.centralAdminRoleDnTemplate.replace("ACCOUNT_NUMBER", accountId);
    }

    private void deleteCentralAdmin(String centralAdminRoleDn, String identityDn) throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("deleteCentralAdmin");

        logger.info(LOGTAG + "Preparing to delete central admin roles:");
        logger.info(LOGTAG + "centralAdminRoleDn is: " + centralAdminRoleDn);
        logger.info(LOGTAG + "identityDn is: " + identityDn);

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
            roleAssignment.setRoleAssignmentActionType("revoke");
            roleAssignment.setRoleAssignmentType("USER_TO_ROLE");
            ExplicitIdentityDNs identityDNs = roleAssignment.newExplicitIdentityDNs();
            identityDNs.addDistinguishedName(identityDn);
            roleAssignment.setExplicitIdentityDNs(identityDNs);
            RoleDNs roleDNs = roleAssignment.newRoleDNs();
            roleDNs.addDistinguishedName(centralAdminRoleDn);
            roleAssignment.setRoleDNs(roleDNs);
            roleAssignment.setReason("Account deprovisioning");
            logger.info(LOGTAG + "Role assignment XML is: " + roleAssignment.toXmlString());
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
            logger.info(LOGTAG + "Deleting central admin role");
            roleAssignment.delete("Delete", requestService);
        } catch (EnterpriseObjectDeleteException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } finally {
            this.idmServiceProducerPool.releaseProducer((PointToPointProducer) requestService);
        }

        long started = System.currentTimeMillis();
        long time = System.currentTimeMillis() - started;
        logger.info(LOGTAG + "Completed in " + time + "ms.");
    }

    private List<RoleAssignment> getCentralAdmins(String roleDn) throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("getCentralAdmins");
        logger.info(LOGTAG + "Begin getting list of central admin assignments.");
        logger.info(LOGTAG + "roleDn is: " + roleDn);

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
