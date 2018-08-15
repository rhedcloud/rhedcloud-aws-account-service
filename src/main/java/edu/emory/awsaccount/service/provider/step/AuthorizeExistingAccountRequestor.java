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
import java.util.Properties;

import javax.jms.JMSException;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;
import org.openeai.utils.sequence.Sequence;
import org.openeai.utils.sequence.SequenceException;

import com.amazon.aws.moa.jmsobjects.user.v1_0.AccountUser;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.ProviderException;
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

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountrequestor.init] ";
		
		logger.info(LOGTAG + "Getting custom step properties...");
		String adminRoleTemplate = getProperties()
				.getProperty("adminRoleTemplate", null);
		setAdminRoleDnTemplate(adminRoleTemplate);
		logger.info(LOGTAG + "adminRoleTemplate is: " + 
				getAdminRoleDnTemplate());
		
		String centralAdminRoleTemplate = getProperties()
				.getProperty("centralAdminRoleTemplate", null);
		setCentralAdminRoleDnTemplate(centralAdminRoleTemplate);
		logger.info(LOGTAG + "centralAdminRoleTemplate is: " + 
				getCentralAdminRoleDnTemplate());
		
		logger.info(LOGTAG + "Initialization complete.");
		
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountrequestor.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean isAuthorized = false;
		List<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		
		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		ProvisioningStep step = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		String sAllocateNewAccount = getResultProperty(step, "allocateNewAccount");
		boolean allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
		
		// If allocateNewAccount is false, send a RoleAssignment.Query-Request 
		// messages to the IdmService to determine if the user is an administrator
		// or central administrator of the account.
		if (allocateNewAccount == false) {
			
			
			
		}
		// If allocateNewAccount is true, there is nothing to do.
		// update the properties and complete the step.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"The account sequence was not incremented.");
		}
		
		// Update the step.
		if (isAuthorized == true || allocateNewAccount == false)
    	update(COMPLETED_STATUS, SUCCESS_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[DetermineNewAccountSequence.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	props.add(buildProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE));
    	Property prop = buildProperty("accountSequenceNumber", "10000");
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[DetermineNewAccountSequence.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	props.add(buildProperty("stepExecutionMethod", FAILURE_EXEC_TYPE));
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[DetermineNewAccountSequence.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, null);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setAdminRoleDnTemplate(String template) 
		throws StepException {
		
		String LOGTAG = "[AuthorizeExistingAccountRequestor.setAdminRoleDnTemplate] ";
		
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
		
		String LOGTAG = "[AuthorizeExistingAccountRequestor.setCentralAdminRoleDnTemplate] ";
		
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
	
	private List<RoleAssignment> roleAssignmentQuery(String roleDn, String userId) 
		throws ProviderException {
		
    	// Query the IDM service for all users in the named role
    	// Get a configured AccountUser, RoleAssignment, and 
    	// RoleAssignmentQuerySpecification from AppConfig
    	AccountUser accountUser = new AccountUser();
		RoleAssignment roleAssignment = new RoleAssignment();
    	RoleAssignmentQuerySpecification querySpec = new RoleAssignmentQuerySpecification();
		try {
			accountUser = (AccountUser)m_appConfig
					.getObjectByType(accountUser.getClass().getName());
			roleAssignment = (RoleAssignment)m_appConfig
				.getObjectByType(roleAssignment.getClass().getName());
			querySpec = (RoleAssignmentQuerySpecification)m_appConfig
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the querySpec.
		try {
			querySpec.setRoleDN(roleDn);
			querySpec.setUserDN(userDn);
			querySpec.setIdentityType("USER");
			querySpec.setDirectAssignOnly("true");
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
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
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the RoleAssignments for the Administrator Role.
		List roleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			roleAssignments = roleAssignment.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for RoleAssignments for " +
				"roleDn " + roleDn + " in " + time + " ms. Returned " + 
				roleAssignments.size() + " users in the role.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		return roleAssignments;
	}
	
}
