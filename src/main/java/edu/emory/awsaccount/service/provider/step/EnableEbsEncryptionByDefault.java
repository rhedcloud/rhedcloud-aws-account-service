/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;

import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
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
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeVpnConnectionsRequest;
import com.amazonaws.services.ec2.model.DescribeVpnConnectionsResult;
import com.amazonaws.services.ec2.model.EnableEbsEncryptionByDefaultRequest;
import com.amazonaws.services.ec2.model.EnableEbsEncryptionByDefaultResult;
import com.amazonaws.services.ec2.model.VpnConnection;
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
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;

/**
 * If this is a new account, enable EBS encryption by default.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 March 2020
 **/
public class EnableEbsEncryptionByDefault extends AbstractStep implements Step {
	
	private final static String IN_PROGRESS = "IN_PROGRESS";
	private final static String SUCCEEDED = "SUCCEEDED";
	private final static String FAILED = "FAILED";
	private String m_accessKeyId = null;
	private String m_secretKey = null;
	private String m_roleArnPattern = null;
	private int m_roleAssumptionDurationSeconds = 0;
	private AmazonEC2Client m_client = null;
	private List<String> m_regions = null;
	private String LOGTAG = "[EnableEbsEncryptionByDefault] ";

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[EnableEbsEncryptionByDefault.init] ";
		
		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
		
		// Access key
		String accessKeyId = getProperties().getProperty("accessKeyId", null);
		setAccessKeyId(accessKeyId);
		logger.info(LOGTAG + "accessKeyId is: " + getAccessKeyId());
		
		// Secret key
		String secretKey = getProperties().getProperty("secretKey", null);
		setSecretKey(secretKey);
		logger.info(LOGTAG + "secretKey is: present");
		
		// Set the roleArnPattern property
		setRoleArnPattern(getProperties().getProperty("roleArnPattern", null));
		logger.info(LOGTAG + "roleArnPattern property is: " + getRoleArnPattern());
		
		// Set the roleAssumptionDurationSeconds property
		setRoleAssumptionDurationSeconds(getProperties()
			.getProperty("roleAssumptionDurationSeconds", null));
		logger.info(LOGTAG + "roleAssumptionDurationSeconds is: " +
			getRoleAssumptionDurationSeconds());
		
		// Set the list of AWS regions
		String regionString = getProperties().getProperty("regions", null);
		logger.info(LOGTAG + "regions property is: " + regionString);
		if (regionString == null) {
			String errMsg = "No AWS regions provided in the properties. Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		else {
			List<String> regions = Arrays.asList(regionString.split("\\s*,\\s*"));
			setRegions(regions);
			logger.info(LOGTAG + "Regions list is: " + String.join(",", getRegions()));
		}
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[EnableEbsEncryptionByDefault.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean encryptionSet = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
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
				"Cannot determine whether or not to authorize the new account " +
				"requestor.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Get the newAccountId property from the GENERATE_NEW_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step2 = getProvisioningStepByType("GENERATE_NEW_ACCOUNT");
		String newAccountId = null;
		if (step2 != null) {
			logger.info(LOGTAG + "Step GENERATE_NEW_ACCOUNT found.");
			newAccountId = getResultProperty(step2, "newAccountId");
			logger.info(LOGTAG + "Property newAccountId from preceding " +
				"step is: " + newAccountId);
			addResultProperty("newAccountId", newAccountId);
		}
		else {
			String errMsg = "Step GENERATE_NEW_ACCOUNT not found. Cannot " +
				"determine whether or not to authorize the new account " +
				"requestor.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// If allocateNewAccount is true and newAccountId is not null,
		// set the EBS encryption by default for all configured regions.
		if (allocateNewAccount && newAccountId != null) {
			logger.info(LOGTAG + "allocateNewAccount is true and newAccountId is " + 
				newAccountId + ". Setting EBS encryption by default.");
			
			List<String> regions = getRegions();
			ListIterator li = regions.listIterator();
			while (li.hasNext()) {
				String region = (String)li.next();
				logger.info(LOGTAG + "Setting EBS encryption by default for region: " + region);
				
				// Build the EC2 client.
				AmazonEC2Client client = buildAmazonEC2Client(newAccountId, region);
				
				// Build the request.
				EnableEbsEncryptionByDefaultRequest request = 
					new EnableEbsEncryptionByDefaultRequest();
				
				
				EnableEbsEncryptionByDefaultResult result = null;
				try {
					logger.info(LOGTAG + "Sending the encryption by default request...");
					long queryStartTime = System.currentTimeMillis();
					result = client.enableEbsEncryptionByDefault(request);
					long queryTime = System.currentTimeMillis() - queryStartTime;
					logger.info(LOGTAG + "received response to encryption by default " +
						"request in queryTime ms.");
				}
				catch (Exception e) {
					String errMsg = "An error occurred setting EBS encryption by default. " +
						"The exception is: " + e.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new StepException(errMsg, e);
				}
			}
			encryptionSet = true;
		}
		// If allocateNewAccount is false, log it and add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to set EBS encryption by default, because it " +
				"already set at the time the account was created.");
			addResultProperty("allocateNewAccount", 
				Boolean.toString(allocateNewAccount));
		}
		
		// Update the step.
		if (allocateNewAccount == false || encryptionSet == true) {
			update(COMPLETED_STATUS, SUCCESS_RESULT);
		}
		else update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[EnableEbsEncryptionByDefault.simulate] ";
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
			"[EnableEbsEncryptionByDefault.fail] ";
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
		String LOGTAG = getStepTag() + 
			"[EnableEbsEncryptionByDefault.rollback] ";
		
		logger.info(LOGTAG + "Rollback called, nothing to rollback.");
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private AmazonEC2Client getAmazonEC2Client() {
		return m_client;
	}
	
	private void setAccessKeyId (String accessKeyId) throws 
		StepException {
	
		if (accessKeyId == null) {
			String errMsg = "accessKeyId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		
		m_accessKeyId = accessKeyId;
	}

	private String getAccessKeyId() {
		return m_accessKeyId;
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
	
	/**
	 * @param String, the pattern of the role to assume
	 * <P>
	 * This method sets the pattern of the role to assume
	 */
	private void setRoleArnPattern(String pattern) throws StepException {
		
		if (pattern == null) {
			String errMsg = "roleArnPattern property is null. " +
				"Can't assume role in target accounts. Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_roleArnPattern = pattern;
	}

	/**
	 * @return String, the pattern of the role to assume
	 * <P>
	 * This method returns the pattern of the role to assume
	 */
	private String getRoleArnPattern() {
		return m_roleArnPattern;
	}
	
	/**
	 * @param String, the role assumption duration
	 * <P>
	 * This method sets the role assumption duration
	 */
	private void setRoleAssumptionDurationSeconds(String seconds) throws StepException {
		
		if (seconds == null) {
			String errMsg = "roleAssumptionDurationSeconds property is null. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_roleAssumptionDurationSeconds = Integer.parseInt(seconds);
	}

	
	/**
	 * @return int, the role assumption duration
	 * <P>
	 * This method returns the role assumption duration in seconds
	 */
	private int getRoleAssumptionDurationSeconds() {
		return m_roleAssumptionDurationSeconds;
	}			
	
	/**
     * 
     * @param String, accountId
     * @param String, region
     * <P>
     * @return, Amazon EC2 client connected to the correct
     * account with the correct role
     * 
     */
    private AmazonEC2Client buildAmazonEC2Client(String accountId, String region) {
    	String LOGTAG = getStepTag() + "[buildAmazonEC2Client] ";
    	
    	// Build the roleArn of the role to assume from the base ARN and 
        // the account number in the query spec.
        logger.info(LOGTAG + "The account targeted by this request is: " + accountId);
        logger.info(LOGTAG + "The region targeted by this request is: " + region);
        logger.info(LOGTAG + "The roleArnPattern is: " + getRoleArnPattern());
        String roleArn = getRoleArnPattern().replace("ACCOUNT_NUMBER", accountId);
        logger.info(LOGTAG + "Role ARN to assume for this request is: " + roleArn); 
        		
		// Instantiate a basic credential provider
        BasicAWSCredentials creds = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);      
        
        // Create the STS client
        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard()
        								.withCredentials(cp)
        								.withRegion(region)
        								.build();       
        
        // Assume the appropriate role in the appropriate account.
        AssumeRoleRequest assumeRequest = new AssumeRoleRequest().withRoleArn(roleArn)
        	.withDurationSeconds(getRoleAssumptionDurationSeconds())
        	.withRoleSessionName("AwsAccountService");

        AssumeRoleResult assumeResult = sts.assumeRole(assumeRequest);
        Credentials credentials = assumeResult.getCredentials();

        // Instantiate a credential provider
        BasicSessionCredentials temporaryCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(), credentials.getSecretAccessKey(), credentials.getSessionToken());
        AWSStaticCredentialsProvider credProvider = new AWSStaticCredentialsProvider(temporaryCredentials);
        
        // Create the EC2 client
        AmazonEC2Client ec2c = 
        	(AmazonEC2Client)AmazonEC2ClientBuilder
        	.standard().withCredentials(credProvider).withRegion(region).build();
    
        return ec2c;
    }
    
    private void setRegions(List<String> regions) {
    	m_regions = regions;
    }
    
    private List<String> getRegions() {
    	return m_regions;
    }
}
