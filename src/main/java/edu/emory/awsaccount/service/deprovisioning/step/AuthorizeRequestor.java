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
import java.util.ListIterator;
import java.util.Properties;

/**
 * <code>AuthorizeRequestor</code> determines if the requesting account is authorized to perform deprovisioning for the given account.
 *
 * @author Darryl L. Pierce (dpierce@surgeforward.com)
 * @version 1.0 - 12 May 2020
 */
public class AuthorizeRequestor extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "AuthorizeRequestor";
    private ProducerPool idmServiceProducerPool;
    private String adminRoleDnTemplate;
    private String centralAdminRoleDnTemplate;
    private String userDnTemplate;

    @Override
    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        super.init(deprovisioningId, props, aConfig, adp);
        String LOGTAG = createLogTag("init");
        logger.info(LOGTAG + "Begin initialization");

        // This step needs to send messages to the AWS account service
        // to authorize requestors.
        ProducerPool p2p1 = null;
        try {
            p2p1 = (ProducerPool) getAppConfig().getObject("IdmServiceProducerPool");
            setIdmServiceProducerPool(p2p1);
        } catch (EnterpriseConfigurationObjectException error) {
            String message = "An error occurred retrieving an object from AppConfig. The exception is: " + error.getMessage();
            logger.fatal(LOGTAG + message);
            throw new StepException(message);
        }

        logger.info(LOGTAG + "Getting custom step properties...");
        String adminRoleTemplate = getProperties().getProperty("adminRoleDnTemplate", null);
        setAdminRoleDnTemplate(adminRoleTemplate);
        logger.info(LOGTAG + "adminRoleDnTemplate is: " + adminRoleTemplate);

        String centralAdminRoleTemplate = getProperties().getProperty("centralAdminRoleDnTemplate", null);
        setCentralAdminRoleDnTemplate(centralAdminRoleTemplate);
        logger.info(LOGTAG + "centralAdminRoleDnTemplate is: " + centralAdminRoleTemplate);

        String userDnTemplate = getProperties().getProperty("userDnTemplate", null);
        setUserDnTemplate(userDnTemplate);
        logger.info(LOGTAG + "userDnTemplate is: " + userDnTemplate);

        logger.info(LOGTAG + "Initialization complete.");
    }

    @Override
    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("rollback");

        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void setUserDnTemplate(String userDnTemplate) throws StepException {
        String LOGTAG = createLogTag("setUserDnTemplate");
        logger.info("Setting userDnTemplate to: " + userDnTemplate);

        if (userDnTemplate == null) {
            String message = "userDnTemplate cannot be null";
            logger.error(LOGTAG + message);
            throw new StepException(message);
        }

        this.userDnTemplate = userDnTemplate;
    }

    private void setCentralAdminRoleDnTemplate(String centralAdminRoleTemplate) throws StepException {
        String LOGTAG = createLogTag("setCentralAdminRoleDnTemplate");
        logger.info("Setting setCentralAdminRoleDnTemplate to: " + centralAdminRoleTemplate);

        if (centralAdminRoleTemplate == null) {
            String message = "centralAdminRoleTemplate cannot be null";
            logger.error(LOGTAG + message);
            throw new StepException(message);
        }

        this.centralAdminRoleDnTemplate = centralAdminRoleTemplate;
    }

    private void setAdminRoleDnTemplate(String adminRoleTemplate) throws StepException {
        String LOGTAG = createLogTag("setAdminRoleDnTemplate");
        logger.info(LOGTAG + "Setting adminRoleDnTemplate to: " + adminRoleTemplate);

        if (adminRoleTemplate == null) {
            String message = "adminRoleTemplate cannot be null";
            logger.error(LOGTAG + message);
            throw new StepException(message);
        }

        this.adminRoleDnTemplate = adminRoleTemplate;
    }

    private void setIdmServiceProducerPool(ProducerPool pool) {
        this.idmServiceProducerPool = pool;
    }

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
    }

    @Override
    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("run");
        logger.info(LOGTAG + "Begin running the step.");

        boolean isAuthorized = false;

        List<Property> props = new ArrayList<Property>();
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business login */

        logger.info(LOGTAG + "Determine if the requestor is authorized to deprovision this account");
        String authenticatedRequestorUserId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAuthenticatedRequestorUserId();
        logger.info(LOGTAG + "authenticatedRequestorUserId is: " + authenticatedRequestorUserId);
        addResultProperty("authenticatedRequestorUserId", authenticatedRequestorUserId);
        List<RoleAssignment> roleAssignments = getRoleAssignments(authenticatedRequestorUserId);

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        logger.info(LOGTAG + "accountId is: " + accountId);
        addResultProperty("accountId", accountId);

        addResultProperty("adminRoleDnTemplate", this.adminRoleDnTemplate);

        String adminRoleDn = getAdminRoleDn(accountId);
        logger.info(LOGTAG + "adminRoleDn is: " + adminRoleDn);
        addResultProperty("adminRoleDn", adminRoleDn);

        boolean isInAdminRole = getIsUserInRole(adminRoleDn, roleAssignments);
        logger.info(LOGTAG + "isInAdminRole is: " + isInAdminRole);
        addResultProperty("isInAdminRole", String.valueOf(isInAdminRole));

        String centralAdminRoleDn = getCentralAdminRoleDn(accountId);
        logger.info(LOGTAG + "centralAdminRoleDn is: " + centralAdminRoleDn);
        addResultProperty("centralAdminRoleDn", centralAdminRoleDn);

        boolean isInCentralAdminRole = getIsUserInRole(centralAdminRoleDn, roleAssignments);
        logger.info(LOGTAG + "isInCentralAdminRole is: " + isInCentralAdminRole);
        addResultProperty("isInCentralAdminRole", String.valueOf(isInCentralAdminRole));

        isAuthorized = (isInAdminRole || isInCentralAdminRole);
        logger.info(LOGTAG + "isAuthorized is: " + isAuthorized);
        addResultProperty("isAuthorized", String.valueOf(isAuthorized));

        /* end business logic */

        // if the user is authorized then the provisioning step completed successfully
        if (isAuthorized) {
            logger.info(LOGTAG + "Step succeeded");
            update(COMPLETED_STATUS, SUCCESS_RESULT);
        } else {
            logger.info(LOGTAG + "Step failed");
            update(COMPLETED_STATUS, FAILURE_RESULT);
        }

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return props;
    }

    private String getCentralAdminRoleDn(String accountId) {
        return this.centralAdminRoleDnTemplate.replace("ACCOUNT_NUMBER", accountId);
    }

    private boolean getIsUserInRole(String roleDn, List<RoleAssignment> roleAssignments) {
        ListIterator li = roleAssignments.listIterator();
        while (li.hasNext()) {
            RoleAssignment ra = (RoleAssignment) li.next();
            if (ra.getRoleDN().equalsIgnoreCase(roleDn)) {
                // determined that the user has an admin role
                return true;
            }
        }

        // no admin role was found
        return false;
    }

    private String getAdminRoleDn(String accountId) {
        return this.adminRoleDnTemplate.replace("ACCOUNT_NUMBER", accountId);
    }

    List<RoleAssignment> getRoleAssignments(String userId) throws StepException {
        String LOGTAG = this.createLogTag("getRoleAssignments");

        RoleAssignment roleAssignment = null;
        RoleAssignmentQuerySpecification querySpecification = null;

        try {
            roleAssignment = (RoleAssignment) getAppConfig().getObjectByType(RoleAssignment.class.getName());
            querySpecification = (RoleAssignmentQuerySpecification) getAppConfig().getObjectByType(RoleAssignmentQuerySpecification.class.getName());
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        String userDn = getUserDn(userId);
        logger.info(LOGTAG + "userDn is: " + userDn);

        try {
            querySpecification.setUserDN(userDn);
            querySpecification.setIdentityType("USER");
            querySpecification.setDirectAssignOnly("true");
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

        List<RoleAssignment> result = null;

        try {
            result = roleAssignment.query(querySpecification, requestService);
            logger.info(LOGTAG + "Number of roles returned: " + result.size());
            logger.info(LOGTAG + "Adding role assignments to result properties");
            logger.info(LOGTAG + "totalRoleAssignments is:" + String.valueOf(result.size()));
            for (int index = 0; index < result.size(); index++) {
                String propertyLabel = "roleAssignment" + String.valueOf(index);
                RoleAssignment role = result.get(index);
                logger.info(LOGTAG + propertyLabel + " is: " + role.getRoleDN());
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
        logger.info(LOGTAG + "Query for role assignments completed in " + time + "ms.");

        return result;
    }

    private String getUserDn(String userId) {
        return this.userDnTemplate.replace("USER_ID", userId);
    }

    @Override
    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("simulate");
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("isAuthorized", "true");
        addResultProperty("accountId", getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId());

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        return getResultProperties();
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
}
