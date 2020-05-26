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
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class DeleteCentralAdminsFromAdminRole extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteCentralAdminsFromAdminRole";
    private ProducerPool idmServiceProducerPool;
    private String centralAdminRoleDn;

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

        String centralAdminRoleDn = getProperties().getProperty("centralAdminRoleDn", null);
        logger.info(LOGTAG + "centralAdminRoleDn is: " + centralAdminRoleDn);
        setCentralAdminRoleDn(centralAdminRoleDn);
    }

    private void setCentralAdminRoleDn(String centralAdminRoleDn) throws StepException {
        if (centralAdminRoleDn == null) {
            throw new StepException("centralAdminRoleDnTemplate cannot be null");
        }
        this.centralAdminRoleDn = centralAdminRoleDn;
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

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        logger.info(LOGTAG + "accountId is: " + accountId);

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */

        // get the list of central admins
        List<RoleAssignment> centralAdmins = this.getCentralAdmins(this.centralAdminRoleDn);
        logger.info(LOGTAG + "Found " + centralAdmins.size() + " central administrator(s)");

        // delete the central admins from the central admin role
        if (centralAdmins.size() > 0) {
            for (int index = 0; index < centralAdmins.size(); index++) {
                this.deleteCentralAdmin(this.centralAdminRoleDn, centralAdmins.get(index));
            }
        } else {
            logger.info(LOGTAG + " No admins to be removed");
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private void deleteCentralAdmin(String centralAdminRoleDn, RoleAssignment roleAssignment) {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("deleteCentralAdmin");


        long started = System.currentTimeMillis();
        long time = System.currentTimeMillis() - started;
        logger.info(LOGTAG + "Completed in " + time + "ms.");
    }

    private List<RoleAssignment> getCentralAdmins(String centralAdminRoleDn) throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("getCentralAdmins");
        logger.info(LOGTAG + "Begin getting list of central admin assignments.");

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
            roleAssignmentQuerySpecification.setRoleDN(centralAdminRoleDn);
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
            Set<String> uniqueIds = new HashSet<>();
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
