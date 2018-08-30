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

import org.openeai.config.AppConfig;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.ListAccountsRequest;
import com.amazonaws.services.organizations.model.ListAccountsResult;
import com.amazonaws.services.organizations.model.MoveAccountRequest;
import com.amazonaws.services.organizations.model.MoveAccountResult;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If a new account was just created, place it in the admin organization.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 30 August 2018
 **/
public class MoveAccountToAdminOrg extends AbstractStep implements Step {
	
	private String m_accessKey = null;
	private String m_secretKey = null;
	private String m_sourceParentId = null;
	private String m_destinationParentId = null;
	
	private AWSOrganizationsClient m_awsOrganizationsClient = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[MoveAccountToAdminOrg.init] ";
		
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
		
		String destinationParentId = getProperties().getProperty("destinationParentId", null);
		setDestinationParentId(destinationParentId);
		logger.info(LOGTAG + "destinationParentId is: " + getDestinationParentId());
	
		
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
		String LOGTAG = getStepTag() + "[MoveAccountToAdminOrg.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean accountMoved = false;
		
		// Return properties
		List<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		
		// Get the allocatedNewAccount property from the
		// GENERATE_NEW_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step1 = getProvisioningStepByType("GENERATE_NEW_ACCOUNT");
		boolean allocatedNewAccount = false;
		String newAccountId = null;
		if (step1 != null) {
			logger.info(LOGTAG + "Step GENERATE_NEW_ACCOUNT found.");
			String sAllocatedNewAccount = getResultProperty(step1, "allocatedNewAccount");
			allocatedNewAccount = Boolean.parseBoolean(sAllocatedNewAccount);
			props.add(buildProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount)));
			logger.info(LOGTAG + "Property allocatedNewAccount from preceding " +
				"step is: " + allocatedNewAccount);
			newAccountId = getResultProperty(step1, "newAccountId");
			props.add(buildProperty("newAccountId", newAccountId));
			logger.info(LOGTAG + "Property newAccountId from preceding " +
				"step is: " + newAccountId);
		}
		else {
			String errMsg = "Step GENERATE_NEW_ACCOUNT not found. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// If allocatedNewAccount is true and the newAccountId is not null,
		// move the account to the admin organizational unit.
		if (allocatedNewAccount == true && newAccountId != null) {
			logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " + 
				"is " + newAccountId + ". Moving the account to the admin org unit.");
			
			// Build the request.
			MoveAccountRequest request = new MoveAccountRequest();
			request.setAccountId(newAccountId);
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
			
			props.add(buildProperty("sourceParentId", getSourceParentId()));	
			props.add(buildProperty("destinationParentId", getDestinationParentId()));
			props.add(buildProperty("movedAccount", Boolean.toString(accountMoved)));
			
			if 	(accountMoved) {
				logger.info(LOGTAG + "Successfully moved account " +
					newAccountId + "to org unit " + getDestinationParentId());
				
			}
			else {
				logger.info(LOGTAG + "Account was not moved.");
			}
		}
				
		// If allocatedNewAccount is false, log it and
		// add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to move an account.");
			props.add(buildProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount)));
			props.add(buildProperty("newAccountId", "not applicable"));
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
		update(COMPLETED_STATUS, stepResult, props);
		
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[MoveAccountToAdminOrg.simulate] ";
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
			"[MoveAccountToAdminOrg.fail] ";
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
		
		super.rollback();
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[MoveAccountToAdminOrg.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, getResultProperties());
		
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
	
		if (id == null) {
			String errMsg = "destinationParentId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_destinationParentId = id;
	}

	private String getDestinationParentId() {
		return m_destinationParentId;
	}
}
