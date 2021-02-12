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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import org.openeai.config.AppConfig;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.policy.Principal;
import com.amazonaws.auth.policy.Resource;
import com.amazonaws.auth.policy.Statement;
import com.amazonaws.auth.policy.Statement.Effect;
import com.amazonaws.auth.policy.actions.S3Actions;
import com.amazonaws.auth.policy.resources.S3ObjectResource;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.S3ResponseMetadata;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.BucketPolicy;
import com.amazonaws.services.s3.model.ListBucketsRequest;
import com.amazonaws.services.s3.model.SetBucketPolicyRequest;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If this is a new account request, create the account.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 17 August 2018
 **/
public class UpdateTemplateBucketPolicy extends AbstractStep implements Step {

	private final static String IN_PROGRESS = "IN_PROGRESS";
	private final static String SUCCEEDED = "SUCCEEDED";
	private final static String FAILED = "FAILED";
	private String m_templateBucketName = null;
	private String m_provisioningRoleName = null;
	private String m_templateBucketPolicyStatementId = null;
	private String m_accessKey = null;
	private String m_secretKey = null;
	private AmazonS3 m_awsS3Client = null;

	public void init (String provisioningId, Properties props,
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
			throws StepException {

		super.init(provisioningId, props, aConfig, vpcpp);

		String LOGTAG = getStepTag() + "[UpdateTemplateBucketPolicy.init] ";

		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");

		String templateBucketName = getProperties().getProperty("templateBucketName", null);
		setTemplateBucketName(templateBucketName);
		logger.info(LOGTAG + "templateBucketName is: " + getTemplateBucketName());

		String provisioningRoleName = getProperties()
			.getProperty("provisioningRoleName", null);
		setProvisioningRoleName(provisioningRoleName);
		logger.info(LOGTAG + "provisioningRoleName is: " +
			getProvisioningRoleName());

		String templateBucketPolicyStatementId = getProperties()
				.getProperty("templateBucketPolicyStatementId", null);
			setTemplateBucketPolicyStatementId(templateBucketPolicyStatementId);
			logger.info(LOGTAG + "templateBucketPolicyStatementId is: " +
				getTemplateBucketPolicyStatementId());

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
		AmazonS3ClientBuilder builder = AmazonS3ClientBuilder
				.standard().withCredentials(new AWSStaticCredentialsProvider(creds));
		builder.setRegion("us-east-1");

		// Initialize the AWS client
		logger.info("Initializing AmazonS3Client...");
		AmazonS3 s3 = (AmazonS3)builder.build();
		logger.info("AmazonS3Client initialized.");

		ListBucketsRequest request = new ListBucketsRequest();

		// Perform a test query
		List<Bucket> result = s3.listBuckets(request);
		logger.info(LOGTAG + "Found " + result.size() + "buckets.");

		// Set the client
		setAwsS3Client(s3);

		logger.info(LOGTAG + "Initialization complete.");
	}

	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[UpdateTemplateBucketPolicy.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		boolean allocatedNewAccount = false;
		String newAccountId = null;

		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		addResultProperty("templateBucketName", getTemplateBucketName());
		addResultProperty("provisioningRoleName", getProvisioningRoleName());

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

		// Get the newAccountId property from the
		// GENERATE_NEW_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step2 = getProvisioningStepByType("GENERATE_NEW_ACCOUNT");
		if (step2 != null) {
			logger.info(LOGTAG + "Step GENERATE_NEW_ACCOUNT found.");
			newAccountId = getResultProperty(step2, "newAccountId");
			addResultProperty("newAccountId", newAccountId);
			logger.info(LOGTAG + "Property newAccountId from preceding " +
				"step is: " + newAccountId);
		}
		else {
			String errMsg = "Step GENERATE_NEW_ACCOUNT not found. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}

		// If allocateNewAccount is true and the newAccountId is not null,
		// update the template bucket policy to allow access from this new
		// account.
		if (allocateNewAccount == true && newAccountId != null) {
			logger.info(LOGTAG + "allocateNewAccount is true and newAccountId " +
				"is " + newAccountId + ". Updating template bucket policy.");

			// Get the current bucket policy.
			BucketPolicy templateBucketPolicy = null;
			try {
				logger.info(LOGTAG + "Getting template bucket policy...");
				templateBucketPolicy = getAwsS3Client()
						.getBucketPolicy(getTemplateBucketName());
				logger.info(LOGTAG + "Retrieved template bucket policy: " +
						templateBucketPolicy.getPolicyText());
			}
			catch (Exception e) {
				String errMsg = "An error occurred querying for the " +
					"BucketPolicy. The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}

			// Update the policy to add a grant for the new account.
			com.amazonaws.auth.policy.Policy newBucketPolicy =
				com.amazonaws.auth.policy.Policy
				.fromJson(templateBucketPolicy.getPolicyText());
			Collection<Statement> statements = newBucketPolicy.getStatements();
			logger.info(LOGTAG + "Current bucket policy has " + statements.size()
				+ " statements.");

			// Iterate over the list of statements and identify
			// the statement that pertains to the template bucket.
			Iterator it = statements.iterator();
			Statement templateBucketStatement = null;
			while (it.hasNext()) {
				Statement st = (Statement)it.next();
				if (st.getId()
					.equalsIgnoreCase(getTemplateBucketPolicyStatementId())) {

					templateBucketStatement = st;
					logger.info(LOGTAG + "Found template bucket statement.");
					break;
				}
			}

			List<Principal> principals =
				templateBucketStatement.getPrincipals();

			logger.info(LOGTAG + "templateBucketStatement has "
				+ principals.size() + " principals.");

			// Build the new principal.
			String p = "arn:aws:iam::" + newAccountId +
				":" + getProvisioningRoleName();

			Principal principal = new Principal(p);
			principals.add(principal);

			logger.info(LOGTAG + "Added new principal " + p +
					" templateBucketStatement now has " +
					principals.size() + " principals.");

			// Update the bucket policy
			newBucketPolicy.setStatements(statements);
			logger.info(LOGTAG + "The new bucket policy has " + statements.size()
				+ " statements.");
			logger.info(LOGTAG + "The new bucket policy JSON is: " +
				newBucketPolicy.toJson());
			logger.info(LOGTAG + "The new bucket policy String is: " +
				newBucketPolicy.toString());

			// Update the bucket policy.
			SetBucketPolicyRequest request = new SetBucketPolicyRequest(getTemplateBucketName(),
				newBucketPolicy.toJson());
			try {
				logger.info(LOGTAG + "Updating template bucket policy...");
				getAwsS3Client().setBucketPolicy(request);
				logger.info(LOGTAG + "Updated template bucket policy.");
			}
			catch (Exception e) {
				String errMsg = "An error occurred updating the " +
					"BucketPolicy. The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				S3ResponseMetadata md = getAwsS3Client().getCachedResponseMetadata(request);
				if (md != null) {
					logger.error(LOGTAG + "S3 response metadata is: " + md.toString());
				}
				else logger.error(LOGTAG + "S3 response metadata is null.");
				throw new StepException(errMsg, e);
			}
		}

		// If allocateNewAccount is false, log it and
		// add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to update the template bucket policy.");
			addResultProperty("allocatedNewAccount",
				Boolean.toString(allocatedNewAccount));
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
			"[UpdateTemplateBucketPolicy.simulate] ";
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
			"[UpdateTemplateBucketPolicy.fail] ";
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
		String LOGTAG = getStepTag() + "[UpdateTemplateBucketPolicy.rollback] ";

		logger.info(LOGTAG + "Rollback called, if a new account was " +
			"created successfully and if it is still in the destination ou, "
			+ "will attempt to move it to the pending delete ou.");
/**
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

			addResultProperty("orgRootId", getOrgRootId()));
			addResultProperty("getPendingDeleteOuId", getPendingDeleteOuId()));
			addResultProperty("movedAccountToPendingDeleteOu",
				Boolean.toString(movedAccountToPendingDeleteOu)));

		}
		// If createdNewAccount or isAccountInOrgRoot is false, there is
		// nothing to roll back. Log it.
		else {
			logger.info(LOGTAG + "No account was created or it is no longer " +
				"in the organization root, so there is nothing to roll back.");
			addResultProperty("movedAccountToPendingDeleteOu",
				"not applicable"));
		}
**/

		update(ROLLBACK_STATUS, SUCCESS_RESULT);

		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}

	private void setAwsS3Client(AmazonS3 client) {
		m_awsS3Client = client;
	}

	private AmazonS3 getAwsS3Client() {
		return m_awsS3Client;
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

	private void setTemplateBucketName (String name) throws
		StepException {

		if (name == null) {
			String errMsg = "templateBucketName property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}

		m_templateBucketName = name;
	}

	private String getTemplateBucketName() {
		return m_templateBucketName;
	}

	private void setProvisioningRoleName (String name) throws
		StepException {

		if (name == null) {
			String errMsg = "provisioningRoleName property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}

		m_provisioningRoleName = name;
	}

	private String getTemplateBucketPolicyStatementId() {
		return m_templateBucketPolicyStatementId;
	}

	private void setTemplateBucketPolicyStatementId (String id) throws
		StepException {

	if (id == null) {
		String errMsg = "templateBucketPolicyStatementId property is null. " +
			"Can't continue.";
		throw new StepException(errMsg);
	}

		m_templateBucketPolicyStatementId = id;
}

private String getProvisioningRoleName() {
	return m_provisioningRoleName;
}

}
