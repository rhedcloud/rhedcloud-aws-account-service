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

/**
 * Delete the grouper group the step is configured to delete.
 * e.g., 123456789:admin deletes the admin group in account 123456789 
 * (and the role assignments)
 * 
 * e.g., 123456789 deletes the account level leaf in Grouper 
 * (after all account level groups have been deleted)
 * <P>
 * 
 * @author Tod Jackson (jtjacks@emory.edu)
 * @version 1.0 - 11 November 2020
 **/

public class DeleteGrouperIdmGroup extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "DeleteGrouperIdmGroup";
    private ProducerPool idmServiceProducerPool;
	private int m_requestTimeoutIntervalInMillis = 600000;
	
	private String roleTemplate;
	private String cloudPlatform;
	private String siteName;

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

        siteName = getProperties().getProperty("siteName", "Rice");
        cloudPlatform = getProperties().getProperty("cloudPlatform", "aws");
        roleTemplate = getProperties().getProperty("roleTemplate", null);
        if (roleTemplate == null) {
            String message = "The 'roleTemplate' property has not been set.  Processing cannot continue";
            logger.fatal(LOGTAG + message);
            throw new StepException(message);
        }
        logger.info(LOGTAG + "roleTemplate is: " + roleTemplate);

		String requestTimeoutInterval = getProperties()
				.getProperty("requestTimeoutIntervalInMillis", "600000");
		int requestTimeoutIntervalInMillis = Integer.parseInt(requestTimeoutInterval);
		setRequestTimeoutIntervalInMillis(requestTimeoutIntervalInMillis);
		logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " + 
			getRequestTimeoutIntervalInMillis());
			
		logger.info(LOGTAG + "Initialization complete.");
    }

    private void setIdmServiceProducerPool(ProducerPool idmServiceProducerPool) {
        this.idmServiceProducerPool = idmServiceProducerPool;
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

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */

        Role role=null;
		try {
			role = (Role) getAppConfig().getObjectByType(Role.class.getName());
	        role.setRoleDN(roleName);
	        role.setRoleName("Not Applicable");
	        role.setRoleDescription(roleName + " Grouper Group for " + siteName);
	        role.setRoleCategoryKey(cloudPlatform);
		} catch (EnterpriseConfigurationObjectException e) {
			e.printStackTrace();
            throw new StepException(e.getMessage(), e);
		} catch (EnterpriseFieldException e) {
			e.printStackTrace();
            throw new StepException(e.getMessage(), e);
		}
        
        this.deleteRole(role, roleName);

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return getResultProperties();
    }

    private void deleteRole(Role role, String roleName) throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("deleteRole");
        logger.info(LOGTAG + "Deleting role.");
        logger.info(LOGTAG + "roleDN is: " + role.getRoleDN());
        logger.info(LOGTAG + "roleName is: " + roleName);

        RequestService requestService = null;

        try {
            logger.info(LOGTAG + "Getting request service");
			PointToPointProducer p2p = 
				(PointToPointProducer)this.idmServiceProducerPool
				.getExclusiveProducer();
			p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
			requestService = (RequestService)p2p;

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

    private String getRoleName(String accountId) {
        return this.roleTemplate.replace("ACCOUNT_NUMBER", accountId);
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

	private void setRequestTimeoutIntervalInMillis(int time) {
		m_requestTimeoutIntervalInMillis = time;
	}
	
	private int getRequestTimeoutIntervalInMillis() {
		return m_requestTimeoutIntervalInMillis;
	}
}
