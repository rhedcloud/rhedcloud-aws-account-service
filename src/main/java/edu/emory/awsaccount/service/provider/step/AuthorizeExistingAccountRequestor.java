/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;

/**
 * If a this is a request for a new VPC in an existing account,
 * send RoleAssignment.Query-Request to determine if the user
 * is an account administrator or central administrator of the
 * account.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 5 August 2018
 **/
public class AuthorizeExistingAccountRequestor extends AbstractStep implements Step {

	private String m_adminRoleDnTemplate = null;
	private String m_centralAdminRoleDnTemplate = null;
	private String m_userDnTemplate = null;
	private ProducerPool m_idmServiceProducerPool = null;

	public void init (String provisioningId, Properties props,
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
			throws StepException {

		super.init(provisioningId, props, aConfig, vpcpp);

		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.init] ";

		// This step needs to send messages to the IDM service
		// to authorize requestors.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("IdmServiceProducerPool");
			setIdmServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}

		logger.info(LOGTAG + "Getting custom step properties...");
		String adminRoleTemplate = getProperties()
				.getProperty("adminRoleDnTemplate", null);
		setAdminRoleDnTemplate(adminRoleTemplate);
		logger.info(LOGTAG + "adminRoleDnTemplate is: " +
				getAdminRoleDnTemplate());

		String centralAdminRoleTemplate = getProperties()
				.getProperty("centralAdminRoleDnTemplate", null);
		setCentralAdminRoleDnTemplate(centralAdminRoleTemplate);
		logger.info(LOGTAG + "centralAdminRoleDnTemplate is: " +
				getCentralAdminRoleDnTemplate());

		String userDnTemplate = getProperties()
				.getProperty("userDnTemplate", null);
		setUserDnTemplate(userDnTemplate);
		logger.info(LOGTAG + "userDnTemplate is: " +
				getUserDnTemplate());

		logger.info(LOGTAG + "Initialization complete.");

	}

	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		boolean isAuthorized = false;
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		ProvisioningStep step = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		String sAllocateNewAccount = getResultProperty(step, "allocateNewAccount");
		boolean allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);

		// If allocateNewAccount is false, send a RoleAssignment.Query-Request
		// messages to the IdmService to determine if the user is an administrator
		// or central administrator of the account.
		if (allocateNewAccount == false) {
			logger.info(LOGTAG + "allocateNewAccount is false...must determine " +
				"if the requestor is authorized to provision a new VPC into " +
				"the existing account.");

			// Get the UserId
			String requestorUserId = getVirtualPrivateCloudProvisioning()
					.getVirtualPrivateCloudRequisition()
					.getAuthenticatedRequestorUserId();
			logger.info(LOGTAG + "requestorUserId is: " + requestorUserId);
			addResultProperty("requestorUserId", requestorUserId);
			List roleAssignments = roleAssignmentQuery(requestorUserId);

			// Get the the AccountId
			String accountId = getVirtualPrivateCloudProvisioning()
					.getVirtualPrivateCloudRequisition()
					.getAccountId();
			logger.info(LOGTAG + "accountId is: " + accountId);
			addResultProperty("accountId", accountId);

			// Build the administrator role dn
			String adminRoleDn = getAdminRoleDn(accountId);
			logger.info(LOGTAG + "adminRoleDn is: " + adminRoleDn);
			addResultProperty("adminRoleDn", adminRoleDn);

			// Determine if the user is in the admin role
			boolean isInAdminRole = isUserInRole(adminRoleDn, roleAssignments);
			addResultProperty("isInAdminRole",
					Boolean.toString(isInAdminRole));
			if (isInAdminRole == true) {
				logger.info(LOGTAG + "User is in the admin role.");
			}
			else {
				logger.info("User is not in the admin role.");
			}

			// Build the administrator role dn
			String centralAdminRoleDn = getCentralAdminRoleDn(accountId);
			logger.info(LOGTAG + "centralAdminRoleDn is: " + centralAdminRoleDn);
			addResultProperty("centralAdminRoleDn", centralAdminRoleDn);

			// Determine if the user is in the central admin role
			boolean isInCentralAdminRole = isUserInRole(centralAdminRoleDn,
				roleAssignments);
			addResultProperty("isInCentralAdminRole",
					Boolean.toString(isInCentralAdminRole));
			if (isInCentralAdminRole == true) {
				logger.info(LOGTAG + "User is in the central admin role.");
			}
			else {
				logger.info("User is not in the central admin role.");
			}

			if (isInAdminRole == true || isInCentralAdminRole) {
				isAuthorized = true;
			}
			addResultProperty("isAuthorized",
					Boolean.toString(isAuthorized));
		}
		// If allocateNewAccount is true, there is nothing to do.
		// update the properties and complete the step.
		else {
			logger.info(LOGTAG + "allocateNewAccount is true. " +
				"A new account will be created, so there is no " +
				"need to authorize the requestor for an existing " +
				"account");
			addResultProperty("allocateNewAccount", "true");
			addResultProperty("isAuthorized", "not applicable");
		}

		// Determine the step result
		String stepResult = null;
		// If this is a new account allocation there was nothing to do,
		// so it is a success.
		if (allocateNewAccount == true) {
			stepResult = SUCCESS_RESULT;
		}
		// If there is no new account allocation and the user is
		// authorized it is a success result.
		if (allocateNewAccount == false && isAuthorized == true) {
			stepResult = SUCCESS_RESULT;
		}
		// If there is no new account allocation and the user is
		// not authorized, this is a failure result.
		if (allocateNewAccount == false && isAuthorized == false) {
			stepResult = FAILURE_RESULT;
		}

		// Update the step
		update(COMPLETED_STATUS, stepResult);

    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");

    	// Return the properties.
    	return getResultProperties();
	}

	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");

		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
    	addResultProperty("isAuthorized", "true");

		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT);

    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

    	// Return the properties.
    	return getResultProperties();
	}

	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");

		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);

    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

    	// Return the properties.
    	return getResultProperties();
	}

	public void rollback() throws StepException {

		super.rollback();

		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " +
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT);

		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}

	private void setUserDnTemplate(String template)
		throws StepException {

		String LOGTAG = getStepTag() +
			"[AuthorizeExistingAccountRequestor.setUserDnTemplate] ";

		if (template == null) {
			String errMsg = "userDnTemplate property is null. " +
				"Can't authorize existing account requestors.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		m_userDnTemplate = template;
	}

	private String getUserDnTemplate() {
		return m_userDnTemplate;
	}

	private String getUserDn(String userId) {
		String userDn = getUserDnTemplate()
			.replace("USER_ID", userId);
		return userDn;
	}

	private void setAdminRoleDnTemplate(String template)
		throws StepException {

		String LOGTAG = getStepTag() +
			"[AuthorizeExistingAccountRequestor.setAdminRoleDnTemplate] ";

		if (template == null) {
			String errMsg = "adminRoleDnTemplate property " +
				"is null. Can't authorize existing account requestors.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		m_adminRoleDnTemplate = template;
	}

	private String getAdminRoleDnTemplate() {
		return m_adminRoleDnTemplate;
	}

	private String getAdminRoleDn(String accountId) {
		String adminRoleDn = getAdminRoleDnTemplate()
			.replace("ACCOUNT_NUMBER", accountId);
		return adminRoleDn;
	}

	private void setCentralAdminRoleDnTemplate(String template)
		throws StepException {

		String LOGTAG = getStepTag() +
			"[AuthorizeExistingAccountRequestor.setCentralAdminRoleDnTemplate] ";

		if (template == null) {
			String errMsg = "centralAdminRoleDnTemplate property " +
				"is null. Can't authorize existing account requestors.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		m_centralAdminRoleDnTemplate = template;
	}

	private String getCentralAdminRoleDnTemplate() {
		return m_centralAdminRoleDnTemplate;
	}

	private String getCentralAdminRoleDn(String accountId) {
		String centralAdminRoleDn = getCentralAdminRoleDnTemplate()
			.replace("ACCOUNT_NUMBER", accountId);
		return centralAdminRoleDn;
	}

	private List<RoleAssignment> roleAssignmentQuery(String userId)
		throws StepException {

		String LOGTAG = getStepTag() +
			"[AuthorizeExistingAccountrequestor.roleAssignmentQuery] ";

    	// Query the IDM service for all users in the named role
    	// Get a configured RoleAssignment and
    	// RoleAssignmentQuerySpecification from AppConfig
		RoleAssignment roleAssignment = new RoleAssignment();
    	RoleAssignmentQuerySpecification querySpec = new RoleAssignmentQuerySpecification();
		try {
			roleAssignment = (RoleAssignment)getAppConfig()
				.getObjectByType(roleAssignment.getClass().getName());
			querySpec = (RoleAssignmentQuerySpecification)getAppConfig()
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, ecoe);
		}

		// Build the UserDN
		String userDn = getUserDn(userId);

		// Set the values of the querySpec.
		try {
			querySpec.setUserDN(userDn);
			querySpec.setIdentityType("USER");
			querySpec.setDirectAssignOnly("true");
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " +
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, efe);
		}

    	// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getIdmServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}
		// Query for the RoleAssignments for the user.
		List<RoleAssignment> roleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			roleAssignments = roleAssignment.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for RoleAssignments for " +
				"userDn " + userDn + " in " + time + " ms. Returned " +
				roleAssignments.size() + " RoleAssignments for user.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " +
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}

		return roleAssignments;
	}

	private boolean isUserInRole(String roleDn, List<RoleAssignment> roleAssignments) {

		boolean isUserInRole = false;

		ListIterator li = roleAssignments.listIterator();
		while (li.hasNext()) {
			RoleAssignment ra = (RoleAssignment)li.next();
			if (ra.getRoleDN().equalsIgnoreCase(roleDn)) {
				isUserInRole = true;
			}
		}

		return isUserInRole;
	}

	private void setIdmServiceProducerPool(ProducerPool pool) {
		m_idmServiceProducerPool = pool;
	}

	private ProducerPool getIdmServiceProducerPool() {
		return m_idmServiceProducerPool;
	}
}
