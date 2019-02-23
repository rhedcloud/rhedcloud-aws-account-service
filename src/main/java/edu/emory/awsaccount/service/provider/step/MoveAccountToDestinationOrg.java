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

import org.openeai.config.AppConfig;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.ListAccountsForParentRequest;
import com.amazonaws.services.organizations.model.ListAccountsForParentResult;
import com.amazonaws.services.organizations.model.ListAccountsRequest;
import com.amazonaws.services.organizations.model.ListAccountsResult;
import com.amazonaws.services.organizations.model.MoveAccountRequest;
import com.amazonaws.services.organizations.model.MoveAccountResult;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If the account is a standard account, pleace it in the standard
 * OU, if it is a HIPAA account place it in the HIPAA OU.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 19 December 2018
 **/
public class MoveAccountToDestinationOrg extends AbstractStep implements Step {
	
	private String m_accessKey = null;
	private String m_secretKey = null;
	private String m_sourceParentId = null;
	private String m_standardDestinationParentId = null;
	private String m_hipaaDestinationParentId = null;
	private String m_destinationParentId = null;
	
	private AWSOrganizationsClient m_awsOrganizationsClient = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[MoveAccountToDestinationOrg.init] ";
		
		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
		
		String accessKey = getProperties().getProperty("accessKey", null);
		setAccessKey(accessKey);
		logger.info(LOGTAG + "accessKey is: " + getAccessKey());
		
		String secretKey = getProperties().getProperty("secretKey", null);
		setSecretKey(secretKey);
		logger.info(LOGTAG + "secretKey is: present");
		
		String sourceParentId = getProperties().getProperty("sourceParentId", null);
		setSourceParentId(sourceParentId);
		logger.info(LOGTAG + "sourceParentId is: " + getSourceParentId());
		
		String standardDestinationParentId = getProperties().getProperty("standardDestinationParentId", null);
		setStandardDestinationParentId(standardDestinationParentId);
		logger.info(LOGTAG + "standardDestinationParentId is: " + getStandardDestinationParentId());
		
		String hipaaDestinationParentId = getProperties().getProperty("hipaaDestinationParentId", null);
		setHipaaDestinationParentId(hipaaDestinationParentId);
		logger.info(LOGTAG + "hipaaDestinationParentId is: " + getHipaaDestinationParentId());

		// Set the AWS account credentials
		BasicAWSCredentials creds = new BasicAWSCredentials(accessKey, 
			secretKey);
		
		// Instantiate an AWS client builder
		AWSOrganizationsClientBuilder builder = AWSOrganizationsClientBuilder
				.standard().withCredentials(new AWSStaticCredentialsProvider(creds));
		builder.setRegion("us-east-1");
		
		// Initialize the AWS client
		logger.info("Initializing AmazonCloudFormationClient...");
		AWSOrganizationsClient client = (AWSOrganizationsClient)builder.build();
		logger.info("AWSOrganizationsClient initialized.");
		ListAccountsRequest request = new ListAccountsRequest();
		
		// Perform a test query
		ListAccountsResult result = client.listAccounts(request);
		logger.info(LOGTAG + "List accounts result: " + result.toString());
		
		// Set the client
		setAwsOrganizationsClient(client);
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[MoveAccountToDestinationOrg.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean accountMoved = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get the allocatedNewAccount property from the
		// GENERATE_NEW_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step1 = getProvisioningStepByType("GENERATE_NEW_ACCOUNT");
		boolean allocatedNewAccount = false;
		String accountId = null;
		if (step1 != null) {
			logger.info(LOGTAG + "Step GENERATE_NEW_ACCOUNT found.");
			String newAccountId = getResultProperty(step1, "newAccountId");
			if (newAccountId.equalsIgnoreCase("not applicable")) {
				accountId = getResultProperty(step1, "accountId");
			}
			else {
				accountId = newAccountId;
			}
			
			addResultProperty("accountId", accountId);
			logger.info(LOGTAG + "Property accountId from preceding " +
				"step is: " + accountId);
		}
		else {
			String errMsg = "Step GENERATE_NEW_ACCOUNT not found. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Determine the compliance class of the account.
		String complianceClass = getVirtualPrivateCloudProvisioning()
				.getVirtualPrivateCloudRequisition().getComplianceClass();
		if (complianceClass.equalsIgnoreCase("HIPAA")) {
			setDestinationParentId(getHipaaDestinationParentId());
		}
		else {
			setDestinationParentId(getStandardDestinationParentId());
		}
		
		logger.info(LOGTAG + "This account has a compliance class of " +
			complianceClass + ". Setting destinationParentId to: " +
			getDestinationParentId());
		addResultProperty("complianceClass", complianceClass);
		addResultProperty("destinationParentId", getDestinationParentId());
			
		// Build the request.
		MoveAccountRequest request = new MoveAccountRequest();
		request.setAccountId(accountId);
		request.setDestinationParentId(getDestinationParentId());
		request.setSourceParentId(getSourceParentId());
		
		// Send the request.
		try {
			logger.info(LOGTAG + "Sending the move account request...");
			long moveStartTime = System.currentTimeMillis();
			MoveAccountResult result = getAwsOrganizationsClient().moveAccount(request);
			long moveTime = System.currentTimeMillis() - moveStartTime;
			logger.info(LOGTAG + "received response to move account request in " +
				moveTime + " ms.");
			accountMoved = true;
		}
		catch (Exception e) {
			String errMsg = "An error occurred moving the account. " +
				"The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		
		addResultProperty("sourceParentId", getSourceParentId());	
		addResultProperty("destinationParentId", getDestinationParentId());
		addResultProperty("movedAccount", Boolean.toString(accountMoved));
		
		if 	(accountMoved) {
			logger.info(LOGTAG + "Successfully moved account " +
				accountId + "to org unit " + getDestinationParentId());
			
		}
		else {
			logger.info(LOGTAG + "Account was not moved.");
		}
		
		// Update the step result.
		String stepResult = FAILURE_RESULT;
		if (accountMoved == true && allocatedNewAccount == true) {
			stepResult = SUCCESS_RESULT;
		}
		if (allocatedNewAccount == false) {
			stepResult = SUCCESS_RESULT;
		}
		
		// Update the step.
		update(COMPLETED_STATUS, stepResult);
		
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[MoveAccountToAdminOrg.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " 
    		+ time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[MoveAccountToAdminOrg.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in "
    		+ time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	public void rollback() throws StepException {
		
		super.rollback();
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[MoveAccountToAdminOrg.rollback] ";
		logger.info(LOGTAG + "Rollback called, if movedAccount is true, " +
			"move it back.");
		
		// Get the result props
		List<Property> props = getResultProperties();
				
		// Get the createdNewAccount and account number properties
		String newAccountId = getResultProperty("newAccountId");
		boolean movedAccount = Boolean.getBoolean(getResultProperty("movedAccount"));	
		boolean isAccountInAdminOu = false;
		boolean movedAccountBackToOrgRoot = false;
		
		// If newAccountId is not null, determine if the account is still in
		// the destination ou.
		if (newAccountId != null) {
			try {
				ListAccountsForParentRequest request = new ListAccountsForParentRequest();
				request.setParentId(getDestinationParentId());
				ListAccountsForParentResult result = 
					getAwsOrganizationsClient().listAccountsForParent(request);
				List<com.amazonaws.services.organizations.model.Account> accounts =
					result.getAccounts();
				ListIterator<com.amazonaws.services.organizations.model.Account> li = 
					accounts.listIterator();
				while (li.hasNext()) {
					com.amazonaws.services.organizations.model.Account account = 
						(com.amazonaws.services.organizations.model.Account)li.next();
					if (account.getId().equalsIgnoreCase(newAccountId));
					isAccountInAdminOu = true;
				}
			}
			catch (Exception e) {
				String errMsg = "An error occurred querying for a list of " +
					"accounts in the admin org. The exception is: " +
					e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}
		}
		
		// If the movedAccount is true and isAccountInAdminOrg is true,
		// move the account to the org root.
		if (movedAccount && isAccountInAdminOu) {
			// Build the request.
			MoveAccountRequest request = new MoveAccountRequest();
			request.setAccountId(newAccountId);
			request.setDestinationParentId(getSourceParentId());
			request.setSourceParentId(getDestinationParentId());
			
			// Send the request.
			try {
				logger.info(LOGTAG + "Sending the move account request...");
				long moveStartTime = System.currentTimeMillis();
				MoveAccountResult result = getAwsOrganizationsClient().moveAccount(request);
				long moveTime = System.currentTimeMillis() - moveStartTime;
				logger.info(LOGTAG + "received response to move account request in " +
					moveTime + " ms.");
				movedAccountBackToOrgRoot = true;
			}
			catch (Exception e) {
				String errMsg = "An error occurred moving the account. " +
					"The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}
			addResultProperty("isAccountInAdminOu", 
					Boolean.toString(isAccountInAdminOu));
			addResultProperty("movedAccountBackToOrgRoot", 
				Boolean.toString(movedAccountBackToOrgRoot));
			
		}
		// If movedAccount or isAccountInAdminOrg is false, there is 
		// nothing to roll back. Log it.
		else {
			logger.info(LOGTAG + "No account was created or it is no longer " +
				"in the organization root, so there is nothing to roll back.");
			addResultProperty("isAccountInAdminOu", 
					Boolean.toString(isAccountInAdminOu));
			addResultProperty("movedAccountBackToOrgRoot", 
				"not applicable");
		}
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setAwsOrganizationsClient(AWSOrganizationsClient client) {
		m_awsOrganizationsClient = client;
	}
	
	private AWSOrganizationsClient getAwsOrganizationsClient() {
		return m_awsOrganizationsClient;
	}
	
	private void setAccessKey (String accessKey) throws 
		StepException {
	
		if (accessKey == null) {
			String errMsg = "accessKey property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		
		m_accessKey = accessKey;
	}

	private String getAccessKey() {
		return m_accessKey;
	}
	
	private void setSecretKey (String secretKey) throws 
		StepException {

		if (secretKey == null) {
			String errMsg = "secretKey property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_secretKey = secretKey;
	}

	private String getSecretKey() {
		return m_secretKey;
	}
	
	private void setSourceParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "sourceParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_sourceParentId = id;
	}

	private String getSourceParentId() {
		return m_sourceParentId;
	}
	
	private void setDestinationParentId (String id) throws 
		StepException {
	
		m_destinationParentId = id;
	}

	private String getDestinationParentId() {
		return m_destinationParentId;
	}
	
	private void setStandardDestinationParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "standardDestinationParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_standardDestinationParentId = id;
	}

	private String getStandardDestinationParentId() {
		return m_standardDestinationParentId;
	}
	
	private void setHipaaDestinationParentId (String id) throws 
		StepException {
	
		if (id == null) {
			String errMsg = "hipaaDestinationParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_hipaaDestinationParentId = id;
	}
	
	private String getHipaaDestinationParentId() {
		return m_hipaaDestinationParentId;
	}
}
