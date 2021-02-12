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
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.CreateAccountRequest;
import com.amazonaws.services.organizations.model.CreateAccountResult;
import com.amazonaws.services.organizations.model.CreateAccountStatus;
import com.amazonaws.services.organizations.model.DescribeCreateAccountStatusRequest;
import com.amazonaws.services.organizations.model.DescribeCreateAccountStatusResult;
import com.amazonaws.services.organizations.model.ListAccountsForParentRequest;
import com.amazonaws.services.organizations.model.ListAccountsForParentResult;
import com.amazonaws.services.organizations.model.ListAccountsRequest;
import com.amazonaws.services.organizations.model.ListAccountsResult;
import com.amazonaws.services.organizations.model.MoveAccountRequest;
import com.amazonaws.services.organizations.model.MoveAccountResult;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;

/**
 * If this is a new account request, create the account.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 17 August 2018
 **/
public class GenerateNewAccount extends AbstractStep implements Step {

	private final static String IN_PROGRESS = "IN_PROGRESS";
	private final static String SUCCEEDED = "SUCCEEDED";
	private final static String FAILED = "FAILED";
	private String m_orgRootId = null;
	private String m_pendingDeleteOuId = null;
	private String m_accountSeriesName = null;
	private String m_accessKey = null;
	private String m_secretKey = null;
	private AWSOrganizationsClient m_awsOrganizationsClient = null;

	public void init (String provisioningId, Properties props,
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
			throws StepException {

		super.init(provisioningId, props, aConfig, vpcpp);

		String LOGTAG = getStepTag() + "[GenerateNewAccount.init] ";

		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");

		String orgRootId = getProperties().getProperty("orgRootId", null);
		setOrgRootId(orgRootId);
		logger.info(LOGTAG + "orgRootId is: " + getOrgRootId());

		String pendingDeleteOuId = getProperties()
			.getProperty("pendingDeleteOuId", null);
		setPendingDeleteOuId(pendingDeleteOuId);
		logger.info(LOGTAG + "pendingDeleteOuId is: " +
			getPendingDeleteOuId());

		String accountSeriesName = getProperties()
			.getProperty("accountSeriesName", null);
		setAccountSeriesName(accountSeriesName);
		logger.info(LOGTAG + "accountSeriesName is: " +
			getAccountSeriesName());

		String accessKey = getProperties().getProperty("accessKey", null);
		setAccessKey(accessKey);
		logger.info(LOGTAG + "accessKey is: " + getAccessKey());

		String secretKey = getProperties().getProperty("secretKey", null);
		setSecretKey(secretKey);
		logger.info(LOGTAG + "secretKey is: present");


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
		String LOGTAG = getStepTag() + "[GenerateNewAccount.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		boolean allocatedNewAccount = false;
		String newAccountId = null;

		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		addResultProperty("accountSeriesName", getAccountSeriesName());

		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step1 = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		boolean allocateNewAccount = false;
		if (step1 != null) {
			logger.info(LOGTAG + "Step DETERMINE_NEW_OR_EXISTING_ACCOUNT found.");
			String sAllocateNewAccount = getResultProperty(step1, "allocateNewAccount");
			allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
			addResultProperty("allocateNewAccount", Boolean.toString(allocateNewAccount));
			logger.info(LOGTAG + "Property allocateNewAccount from preceding " +
				"step is: " + allocateNewAccount);
		}
		else {
			String errMsg = "Step DETERMINE_NEW_OR_EXISTING_ACCOUNT not found. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}

		// Get the accountSequenceNumber property from the
		// DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step2 = getProvisioningStepByType("DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE");
		String accountSequenceNumber = null;
		if (step2 != null) {
			logger.info(LOGTAG + "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE found.");
			accountSequenceNumber = getResultProperty(step2, "accountSequenceNumber");
			addResultProperty("accountSequenceNumber", accountSequenceNumber);
			logger.info(LOGTAG + "Property accountSequenceNumber from preceding " +
				"step is: " + accountSequenceNumber);
		}
		else {
			String errMsg = "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE not found. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}

		// If allocateNewAccount is true and the account e-mail address is not null,
		// create a new account.
		if (allocateNewAccount == true) {
			logger.info(LOGTAG + "allocateNewAccount is true. Creating a new AWS Account.");

			// Get the accountEmailAddress property from the
			// VERIFY_NEW_ACCOUNT_ADMIN_DISTRO_LIST step.
			logger.info(LOGTAG + "Getting properties from preceding steps...");
			ProvisioningStep step3 = getProvisioningStepByType("VERIFY_NEW_ACCOUNT_ADMIN_DISTRO_LIST");
			String accountEmailAddress = null;
			if (step3 != null) {
				logger.info(LOGTAG + "Step VERIFY_NEW_ACCOUNT_ADMIN_DISTRO_LIST found.");
				accountEmailAddress = getResultProperty(step3, "accountEmailAddress");
				addResultProperty("accountEmailAddress", accountEmailAddress);
				logger.info(LOGTAG + "Property accountEmailAddress from preceding " +
					"step is: " + accountEmailAddress);
			}
			else {
				String errMsg = "Step VERIFY_NEW_ACCOUNT_ADMIN_DISTRO_LIST not found. " +
					"Cannot determine the e-mail address for a new account.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}

			// Build account name
			String newAccountName = getAccountSeriesName() + " " +
				accountSequenceNumber;
			addResultProperty("newAccountName", newAccountName);

			// Build the request.
			CreateAccountRequest request = new CreateAccountRequest();
			request.setAccountName(newAccountName);
			request.setEmail(accountEmailAddress);
			request.setIamUserAccessToBilling("ALLOW");

			// Send the request.
			String id = null;
			String state = null;
			try {
				logger.info(LOGTAG + "Sending the account create request...");
				long createStartTime = System.currentTimeMillis();
				CreateAccountResult result = getAwsOrganizationsClient().createAccount(request);
				long createTime = System.currentTimeMillis() - createStartTime;
				id = result.getCreateAccountStatus().getId();
				state = result.getCreateAccountStatus().getState();
				logger.info(LOGTAG + "received response to account create request in " +
					createTime + " ms. Result status for request ID " + id + " is: " + state);
			}
			catch (Exception e) {
				String errMsg = "An error occurred creating the account. " +
					"The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}

			// Wait for the request to complete.
			boolean createComplete = false;
			DescribeCreateAccountStatusRequest casRequest = new DescribeCreateAccountStatusRequest();
			casRequest.setCreateAccountRequestId(id);
			DescribeCreateAccountStatusResult casResult = null;
			while (createComplete == false) {
				logger.info(LOGTAG + "Checking for the staus of the create account transaction...");
				casResult = getAwsOrganizationsClient()
					.describeCreateAccountStatus(casRequest);
				state = casResult.getCreateAccountStatus().getState();
				logger.info(LOGTAG + "Account creation status is: " + state);
				if (state.equals(IN_PROGRESS) == false) {
					createComplete = true;
				}
				else {
					logger.info(LOGTAG + "Waiting to check account creation status again.");
					try {
						Thread.sleep(5000);
					}
					catch (InterruptedException ie) {
						String errMsg = "An error occurred waiting for AWS " +
							"to create the new account. The exception is: " +
							ie.getMessage();
						logger.error(LOGTAG + errMsg);
						throw new StepException(errMsg, ie);
					}
				}
			}

			if (state.equalsIgnoreCase(SUCCEEDED)) {
				allocatedNewAccount = true;
				newAccountId = casResult.getCreateAccountStatus().getAccountId();
				logger.info(LOGTAG + "Successfully created new account: " + newAccountId);
				addResultProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount));
				addResultProperty("newAccountId", newAccountId);
			}
			else {
				allocatedNewAccount = false;
				String failureReason = casResult.getCreateAccountStatus().getFailureReason();
				if (failureReason == null) failureReason = "none returned";
				logger.info(LOGTAG + "Failed to create new account. Failure reason: " + failureReason);
				addResultProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount));
				addResultProperty("failureReason", failureReason);
			}
		}

		// If allocateNewAccount and accountSequenceNumber is false, log it and
		// add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to create a new account.");
			addResultProperty("allocatedNewAccount", Boolean.toString(allocatedNewAccount));
			addResultProperty("newAccountId", "not applicable");
		}

		// Update the step.
		String stepResult = FAILURE_RESULT;
		if (allocateNewAccount == true && allocatedNewAccount == true) {
			stepResult = SUCCESS_RESULT;
		}
		if (allocateNewAccount == false) {
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
			"[GenerateNewAccount.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");

		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);

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
		String LOGTAG = getStepTag() +
			"[GenerateNewAccount.fail] ";
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
		String LOGTAG = getStepTag() + "[GenerateNewAccount.rollback] ";

		logger.info(LOGTAG + "Rollback called, if a new account was " +
			"created successfully and if it is still in the destination ou, "
			+ "will attempt to move it to the pending delete ou.");

		// Get the result props
		List<Property> props = getResultProperties();

		// Get the createdNewAccount and account number properties
		boolean createdNewAccount = Boolean
			.getBoolean(getResultProperty("createdNewAccount"));
		String newAccountId = getResultProperty("newAccountId");
		boolean isAccountInOrgRoot = false;
		boolean movedAccountToPendingDeleteOu = false;

		// If newAccountId is not null, determine if the account is still in
		// the destination ou.
		if (newAccountId != null) {
			try {
				ListAccountsForParentRequest request = new ListAccountsForParentRequest();
				request.setParentId(getOrgRootId());
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
					isAccountInOrgRoot = true;
				}
			}
			catch (Exception e) {
				String errMsg = "An error occurred querying for a list of " +
					"accounts in the org root. The exception is: " +
					e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}
		}

		// If the createdNewAccount is true and isAccountInOrgRoot is true,
		// move the account to the pending delete org unit.
		if (createdNewAccount && isAccountInOrgRoot) {
			// Build the request.
			MoveAccountRequest request = new MoveAccountRequest();
			request.setAccountId(newAccountId);
			request.setDestinationParentId(getPendingDeleteOuId());
			request.setSourceParentId(getOrgRootId());

			// Send the request.
			try {
				logger.info(LOGTAG + "Sending the move account request...");
				long moveStartTime = System.currentTimeMillis();
				MoveAccountResult result = getAwsOrganizationsClient().moveAccount(request);
				long moveTime = System.currentTimeMillis() - moveStartTime;
				logger.info(LOGTAG + "received response to move account request in " +
					moveTime + " ms.");
				movedAccountToPendingDeleteOu = true;
			}
			catch (Exception e) {
				String errMsg = "An error occurred moving the account. " +
					"The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}

			addResultProperty("orgRootId", getOrgRootId());
			addResultProperty("getPendingDeleteOuId", getPendingDeleteOuId());
			addResultProperty("movedAccountToPendingDeleteOu",
				Boolean.toString(movedAccountToPendingDeleteOu));

		}
		// If createdNewAccount or isAccountInOrgRoot is false, there is
		// nothing to roll back. Log it.
		else {
			logger.info(LOGTAG + "No account was created or it is no longer " +
				"in the organization root, so there is nothing to roll back.");
			addResultProperty("movedAccountToPendingDeleteOu",
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

	private void setAccountSeriesName (String name) throws
		StepException {

		if (name == null) {
			String errMsg = "accountSeriesName property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}

		m_accountSeriesName = name;
	}

	private String getAccountSeriesName() {
		return m_accountSeriesName;
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

	private void setOrgRootId (String id) throws
		StepException {

		if (id == null) {
			String errMsg = "orgRootId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}

		m_orgRootId = id;
	}

	private String getOrgRootId() {
		return m_orgRootId;
	}

	private void setPendingDeleteOuId (String id) throws
		StepException {

		if (id == null) {
			String errMsg = "pendingDeleteOuId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}

		m_pendingDeleteOuId = id;
	}

	private String getPendingDeleteOuId() {
		return m_orgRootId;
	}

}
