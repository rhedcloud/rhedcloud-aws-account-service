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
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.jms.JMSException;

import org.apache.commons.io.IOUtils;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Authentication;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Credentials;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.StackParameter;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If this is a new account request, send an 
 * AccountProvisioningAuthorization to determine if the user 
 * is authorized to create a new account.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 10 August 2018
 **/
public class CreateRsAccountCfnStack extends AbstractStep implements Step {
	
	private String m_cloudFormationTemplateUrl = null;
	private String m_cloudFormationTemplateBodyUrl = null;
	private String m_cloudTrailSuffix =null;
	private String m_rhedCloudIdp = null;
	private String m_rhedCloudSamlIssuer = null;
	private String m_rhedCloudSecurityRiskDetectionServiceUserArn = null;
	private String m_rhedCloudAwsAccountServiceUserArn = null;
	private String m_rhedCloudMaintenanceOperatorRoleArn = null;
	private String m_stackName = null;
	private int m_requestTimeoutInterval = 10000;
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private final static String TEMPLATE_BODY_ENCODING = "UTF-8";
	private final static String HIPAA_COMPLIANCE_CLASS = "HIPAA";

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[CreateRsAccountCfnStack.init] ";
		
		// Get the custom step properties
		// requestTimeoutInterval is the time to wait for the
		// response to the request
		String timeout = getProperties().getProperty("requestTimeoutInterval",
			"10000");
		int requestTimeoutInterval = Integer.parseInt(timeout);
		setRequestTimeoutInterval(requestTimeoutInterval);
		logger.info(LOGTAG + "requestTimeoutInterval is: " + 
			getRequestTimeoutInterval());
		
		// cloudFormationTemplateUrl is the S3 bucket URL of the
		// CloudFormation Template
		String cloudFormationTemplateUrl = getProperties()
			.getProperty("cloudFormationTemplateUrl", null);
		setCloudFormationTemplateUrl(cloudFormationTemplateUrl);
		logger.info(LOGTAG + "cloudFormationTemplateUrl is: " + 
			getCloudFormationTemplateUrl());
		
		// cloudFormationTemplateBodyUrl is a non S3 URL to the
		// body of the template if an S3 URL cannot be used.
		String cloudFormationTemplateBodyUrl = getProperties()
			.getProperty("cloudFormationTemplateBodyUrl", null);
		setCloudFormationTemplateBodyUrl(cloudFormationTemplateBodyUrl);
		logger.info(LOGTAG + "cloudFormationTemplateBodyUrl is: " +
			getCloudFormationTemplateBodyUrl());
		
		// cloudTrailSuffix is the final component of the cloudTrailName
		// after account series and account sequence.
		String cloudTrailSuffix = getProperties()
			.getProperty("cloudTrailSuffix", null);
		setCloudTrailSuffix(cloudTrailSuffix);
		logger.info(LOGTAG + "cloudTrailSuffix is: " +
			getCloudTrailSuffix());
		
		// stackName is the name to give the stack.
		String stackName = getProperties()
			.getProperty("stackName", null);
		setStackName(stackName);
		logger.info(LOGTAG + "stackName is: " + getStackName());

		// rhedCloudIdp is the name of the identity provider.
		String rhedCloudIdp = getProperties()
			.getProperty("rhedCloudIdp", null);
		setRhedCloudIdp(rhedCloudIdp);
		logger.info(LOGTAG + "rhedCloudIdp is: " + getRhedCloudIdp());
		
		// rhedCloudSamlIssuer is the name of the SAML issuer.
		String rhedCloudSamlIssuer = getProperties()
			.getProperty("rhedCloudSamlIssuer", null);
		setRhedCloudSamlIssuer(rhedCloudSamlIssuer);
		logger.info(LOGTAG + "rhedCloudSamlIssuer is: " + getRhedCloudSamlIssuer());
		
		// rhedCloudSecurityRiskDetectionServiceUserArn is the ARN that the
		// SecurityRiskDetectionService uses to access linked accounts.
		String rhedCloudSecurityRiskDetectionServiceUserArn = getProperties()
			.getProperty("rhedCloudSecurityRiskDetectionServiceUserArn", null);
		setRhedCloudSecurityRiskDetectionServiceUserArn(rhedCloudSecurityRiskDetectionServiceUserArn);
		logger.info(LOGTAG + "rhedCloudSecurityRiskDetectionServiceUserArn is: " + 
			getRhedCloudSecurityRiskDetectionServiceUserArn());
		
		// rhedCloudAwsAccountServiceUserArn is the ARN that the
		// SecurityRiskDetectionService uses to access linked accounts.
		String rhedCloudAwsAccountServiceUserArn = getProperties()
			.getProperty("rhedCloudAwsAccountServiceUserArn", null);
		setRhedCloudAwsAccountServiceUserArn(rhedCloudAwsAccountServiceUserArn);
		logger.info(LOGTAG + "rhedCloudAwsAccountServiceUserArn is: " + 
			getRhedCloudAwsAccountServiceUserArn());
		
		// rhedCloudMaintenanceOperatorRoleArn...I have no idea what this is
		// but I am guessing it is some role for the ops team.
		String rhedCloudMaintenanceOperatorRoleArn = getProperties()
			.getProperty("rhedCloudMaintenanceOperatorRoleArn", null);
		setRhedCloudMaintenanceOperatorRoleArn(rhedCloudMaintenanceOperatorRoleArn);
		logger.info(LOGTAG + "rhedCloudMaintenanceOperatorRoleArn is: " + 
			getRhedCloudMaintenanceOperatorRoleArn());
		
		// This step needs to send messages to the AWS account service
		// to create stacks.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("AwsAccountServiceProducerPool");
			setAwsAccountServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[CreateRsAccountCfnStack.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean stackCreated = false;
		
		// Return properties
		List<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		
		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step1 = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		boolean allocateNewAccount = false;
		if (step1 != null) {
			logger.info(LOGTAG + "Step DETERMINE_NEW_OR_EXISTING_ACCOUNT found.");
			String sAllocateNewAccount = getResultProperty(step1, "allocateNewAccount");
			allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
			props.add(buildProperty("allocateNewAccount", Boolean.toString(allocateNewAccount)));
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
			props.add(buildProperty("newAccountId", newAccountId));
		}
		else {
			String errMsg = "Step GENERATE_NEW_ACCOUNT not found. Cannot " +
				"determine whether or not to authorize the new account " +
				"requestor.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Get the accountSequenceNumber property from the 
		// DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step3 = 
			getProvisioningStepByType("DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE");
		String accountSequenceNumber = null;
		if (step3 != null) {
			logger.info(LOGTAG + "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE found.");
			accountSequenceNumber = getResultProperty(step3, 
				"accountSequenceNumber");
			logger.info(LOGTAG + "Property accountSequenceNumber from preceding " +
				"step is: " + newAccountId);
			props.add(buildProperty("accountSequenceNumber", 
				accountSequenceNumber));
		}
		else {
			String errMsg = "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE not " +
				"found. Cannot determine account sequence number.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Get the accountAlias property from the 
		// VERIFY_NEW_ACCOUNT_ADMIN_DISTRO_LIST step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step4 = 
			getProvisioningStepByType("VERIFY_NEW_ACCOUNT_ADMIN_DISTRO_LIST");
		String accountAlias = null;
		if (step4 != null) {
			logger.info(LOGTAG + "Step VERIFY_NEW_ACCOUNT_ADMIN_DISTRO_LIST found.");
			accountAlias = getResultProperty(step4, "accountAlias");
			logger.info(LOGTAG + "Property accountAlias from preceding " +
				"step is: " + accountAlias);
			props.add(buildProperty("accountAlias", accountAlias));
		}
		else {
			String errMsg = "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE not " +
				"found. Cannot determine account sequence number.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// If allocateNewAccount is true and newAccountId is not null,
		// send a Stack.Generate-Request to generate the rs-account stack.
		if (allocateNewAccount && newAccountId != null) {
			logger.info(LOGTAG + "allocateNewAccount is true and newAccountId is " + 
				newAccountId + ". Sending a Stack.Generate-Request to create the " +
				"rhedcloud-aws-rs-account stack in a new account.");
			
			// Send an Stack.Generate-Request. Get a configured Stack and requisition
			// object from AppConfig.
			Stack stack = new Stack();
			StackRequisition req = new StackRequisition();
		    try {
		    	stack = (Stack)getAppConfig()
			    		.getObjectByType(stack.getClass().getName());
		    	req = (StackRequisition)getAppConfig()
			    		.getObjectByType(req.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Set the values of the requisition and place them in step props.
		    try {
		    	// AccountId
		    	req.setAccountId(newAccountId);
		    	props.add(buildProperty("accountId", req.getAccountId()));
		    	logger.info(LOGTAG + "accountId: " + req.getAccountId());
		    	
		    	// StackName
		    	req.setStackName(getStackName());
		    	props.add(buildProperty("accountId", req.getAccountId()));
		    	logger.info(LOGTAG + "stackName: " + req.getStackName());
		    	
		    	// Description
		    	req.setDescription("RHEDcloud AWS CloudFormation template for account-level structures and policies");
		    
		    	// DisableRollback
		    	req.setDisableRollback("false");
		    	
		    	// Template URL - we prefer to pull this from an S3 bucket,
		    	// but if we have to we read it from a non-S3 URL.
		    	if (getCloudFormationTemplateUrl() != null) {
		    		req.setTemplateUrl(getCloudFormationTemplateUrl());
		    		props.add(buildProperty("templateUrl", req.getTemplateUrl()));
		    		logger.info(LOGTAG + "templateUrl: " + req.getTemplateUrl());
		    	}
		    	else if (getCloudFormationTemplateBodyUrl() != null) {
		    		req.setTemplateBody(getCloudFormationTemplateBody());
		    		props.add(buildProperty("templateBodyUrl", 
		    			getCloudFormationTemplateBodyUrl()));
		    		logger.info(LOGTAG + "templateBody: " + 
		    			req.getTemplateBody());
		    	}
		    	else {
		    		String errMsg = "No CloudFormation template source " +
		    			"specified. Can't continue.";
		    		logger.error(LOGTAG + errMsg);
		    		throw new StepException(errMsg);
		    	}
		    	
		    	// Set stack parameters
		    	logger.info(LOGTAG + "Setting stack parameters...");
		    	
		    	// CloudTrailName
		    	StackParameter cloudTrailName = req.newStackParameter();
		    	cloudTrailName.setKey("CloudTrailName");
		    	cloudTrailName.setValue(getCloudTrailName(accountAlias));
		    	req.addStackParameter(cloudTrailName);
		    	props.add(buildProperty("CloudTrailName", cloudTrailName.getValue()));
		    	logger.info(LOGTAG + "Parameter CloudTrailName: " + 
		    		cloudTrailName.getValue());
		    	
		    	// AddHIPAAIAMPolicy - Yes/No
		    	StackParameter addHipaaIamPolicy = req.newStackParameter();
		    	addHipaaIamPolicy.setKey("AddHIPAAIAMPolicy");
		    	addHipaaIamPolicy.setValue(getAddHipaaIamPolicy());
		    	req.addStackParameter(addHipaaIamPolicy);
		    	props.add(buildProperty("AddHIPAAIAMPolicy", addHipaaIamPolicy.getValue()));
		    	logger.info(LOGTAG + "Parameter AddHIPAAIAMPolicy: " + 
		    		addHipaaIamPolicy.getValue());
		    	
		    	// RHEDcloudIDP
		    	StackParameter rhedCloudIdp = req.newStackParameter();
		    	rhedCloudIdp.setKey("RHEDcloudIDP");
		    	rhedCloudIdp.setValue(getRhedCloudIdp());
		    	req.addStackParameter(rhedCloudIdp);
		    	props.add(buildProperty("RHEDcloudIDP", rhedCloudIdp.getValue()));
		    	logger.info(LOGTAG + "Parameter RHEDcloudIDP: " + 
		    		rhedCloudIdp.getValue());
		        
		        // RHECcloudSamlIssuer
		    	StackParameter rhedCloudSamlIssuer = req.newStackParameter();
		    	rhedCloudSamlIssuer.setKey("RHEDcloudSamlIssuer");
		    	rhedCloudSamlIssuer.setValue(getRhedCloudSamlIssuer());
		    	req.addStackParameter(rhedCloudSamlIssuer);
		    	props.add(buildProperty("RHEDcloudSamlIssuer", 
		    		rhedCloudSamlIssuer.getValue()));
		    	logger.info(LOGTAG + "Parameter RHEDcloudSamlIssuer: " + 
		    		rhedCloudSamlIssuer.getValue());
		    	
		    	// RHEDcloudSecurityRiskDetectionServiceUserArn
		    	StackParameter rhedCloudSecurityRiskDetectionServiceUserArn = 
		    		req.newStackParameter();
		    	rhedCloudSecurityRiskDetectionServiceUserArn
		    		.setKey("RHEDcloudSecurityRiskDetectionServiceUserArn");
		    	rhedCloudSecurityRiskDetectionServiceUserArn
		    		.setValue(getRhedCloudSecurityRiskDetectionServiceUserArn());
		    	req.addStackParameter(rhedCloudSecurityRiskDetectionServiceUserArn);
		    	props.add(buildProperty("RHEDcloudSecurityRiskDetectionServiceUserArn", 
		    		rhedCloudSecurityRiskDetectionServiceUserArn.getValue()));
		    	logger.info(LOGTAG + 
		    		"Parameter RHEDcloudSecurityRiskDetectionServiceUserArn: " + 
		    		rhedCloudSecurityRiskDetectionServiceUserArn.getValue());
		    	
		        // RHEDcloudAwsAccountServiceUserArn
		    	StackParameter rhedCloudAwsAccountServiceUserArn = req.newStackParameter();
		    	rhedCloudAwsAccountServiceUserArn.setKey("RHEDcloudAwsAccountServiceUserArn");
		    	rhedCloudAwsAccountServiceUserArn.setValue(getRhedCloudAwsAccountServiceUserArn());
		    	req.addStackParameter(rhedCloudAwsAccountServiceUserArn);
		    	props.add(buildProperty("RHEDcloudAwsAccountServiceUserArn",
		    		rhedCloudAwsAccountServiceUserArn.getValue()));
		    	logger.info(LOGTAG + 
		    		"Parameter RHEDcloudAwsAccountServiceUserArn: " + 
		    		rhedCloudAwsAccountServiceUserArn.getValue());
		    	
		        // RHEDcloudMaintenanceOperatorRoleArn
		    	StackParameter rhedCloudMaintenanceOperatorRoleArn = req.newStackParameter();
		    	rhedCloudMaintenanceOperatorRoleArn.setKey("RHEDcloudMaintenanceOperatorRoleArn");
		    	rhedCloudMaintenanceOperatorRoleArn.setValue(getRhedCloudMaintenanceOperatorRoleArn());
		    	req.addStackParameter(rhedCloudMaintenanceOperatorRoleArn);
		    	props.add(buildProperty("RHEDcloudMaintenanceOperatorRoleArn", 
		    		rhedCloudMaintenanceOperatorRoleArn.getValue()));
		    	logger.info(LOGTAG + 
		    		"Parameter RHEDcloudMaintenanceOperatorRoleArn: " + 
		    		rhedCloudMaintenanceOperatorRoleArn.getValue());
		    	
		    	// Add capabilities
		    	String cap1 = "CAPABILITY_IAM";
		    	String cap2 = "CAPABILITY_NAMED_IAM";
		    	req.addCapability(cap1);
		    	req.addCapability(cap2);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "requisition. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the requisition.
		    try {
		    	logger.info(LOGTAG + "Requisition is: " + req.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the requisition " +
		  	    	  "to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
	  	    	throw new StepException(errMsg, xeoe);
		    }    
		    
		    // TODO:Set the message authentication
		    // Authentication auth = stack.getAuthentication();
		    // auth.setAuthUserId(userId);
			
			// Get a request service from the pool and set the timeout interval.
			RequestService rs = null;
			try {
				PointToPointProducer p2p = 
					(PointToPointProducer)getAwsAccountServiceProducerPool()
					.getExclusiveProducer();
				p2p.setRequestTimeoutInterval(getRequestTimeoutInterval());
				rs = (RequestService)p2p;
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a producer " +
					"from the pool. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, jmse);
			}
		    
			List results = null;
			try { 
				long generateStartTime = System.currentTimeMillis();
				results = stack.generate(req, rs);
				long generateTime = System.currentTimeMillis() - generateStartTime;
				logger.info(LOGTAG + "Generated CloudFormation Stack in "
					+ generateTime + " ms. Returned " + results.size() + 
					" result.");
			}
			catch (EnterpriseObjectGenerateException eoge) {
				String errMsg = "An error occurred generating the " +
		    	  "Stack object. The exception is: " + eoge.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoge);
			}
			finally {
				// Release the producer back to the pool
				getAwsAccountServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
			
			if (results.size() == 1) {
				Stack stackResult = (Stack)results.get(0);
				logger.info(LOGTAG + "Stack result is: " + 
					stackResult.getStackStatus());
				props.add(buildProperty("stackStatus", 
						stackResult.getStackStatus()));
				if (stackResult.getStackStatus()
						.equalsIgnoreCase("CREATE_COMPLETE")) {
					stackCreated = true;
				}
			}
			else {
				String errMsg = "Invalid number of results returned from " +
					"Stack.Generate-Request. " +
					results.size() + " results returned. Expected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
		}
		// If allocateNewAccount is false, log it and add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to create the rhedcloud-aws-rs-account stack.");
			props.add(buildProperty("allocateNewAccount", Boolean.toString(allocateNewAccount)));
		}
		
		// Update the step.
		if (allocateNewAccount == false || stackCreated == true) {
			update(COMPLETED_STATUS, SUCCESS_RESULT, props);
		}
		else update(COMPLETED_STATUS, FAILURE_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[CreateRsAccountCfnStack.simulate] ";
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
			"[CreateRsAccountCfnStack.fail] ";
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
			"[CreateRsAccountCfnStack.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, getResultProperties());
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}

	private void setRequestTimeoutInterval(int i) {
		m_requestTimeoutInterval = i;
	}
	
	private int getRequestTimeoutInterval() {
		return m_requestTimeoutInterval;
	}
	
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}
	
	private void setCloudFormationTemplateUrl (String url) throws 
		StepException {
	
		m_cloudFormationTemplateUrl = url;
	}

	private String getCloudFormationTemplateUrl() {
		return m_cloudFormationTemplateUrl;
	}
	
	private void setCloudFormationTemplateBodyUrl (String url) throws 
		StepException {

		m_cloudFormationTemplateBodyUrl = url;
	}

	private String getCloudFormationTemplateBodyUrl() {
		return m_cloudFormationTemplateBodyUrl;
	}
	
	private String getCloudTrailName(String accountSeriesName, 
		String accountSequenceNumber, String cloudTrailSuffix) {
		
		String cloudTrailName = accountSeriesName + "-" 
			+ accountSequenceNumber + "-" + cloudTrailSuffix;
		
		return cloudTrailName;
	}
	
	private void setCloudTrailSuffix (String suffix) throws 
		StepException {

		if (suffix == null) {
			String errMsg = "cloudTrailSuffix property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		m_cloudTrailSuffix = suffix;
	}

	private String getCloudTrailSuffix() {
		return m_cloudTrailSuffix;
	}
	
	private String getCloudTrailName(String accountAlias) {
		String cloudTrailName = accountAlias + "-" + getCloudTrailSuffix();
		return cloudTrailName;
	}
	
	private String getAddHipaaIamPolicy() {
		
		String addHipaaIamPolicy = "No";
		
		VirtualPrivateCloudRequisition req = 
			getVirtualPrivateCloudProvisioning()
			.getVirtualPrivateCloudRequisition();
		
		if (req.getComplianceClass()
			.equalsIgnoreCase(HIPAA_COMPLIANCE_CLASS)) {
			addHipaaIamPolicy = "Yes";
		}
		
		return addHipaaIamPolicy;
	}
	
	private void setRhedCloudIdp (String idp) throws 
		StepException {
	
		if (idp == null) {
			String errMsg = "rhedCloudIdp property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		m_rhedCloudIdp = idp;
	}

	private String getRhedCloudIdp() {
		return m_rhedCloudIdp;
	}
	
	private void setRhedCloudSamlIssuer (String issuer) throws 
		StepException {
	
		if (issuer == null) {
			String errMsg = "rhedCloudSamlIssuer property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		m_rhedCloudSamlIssuer = issuer;
	}

	private String getRhedCloudSamlIssuer() {
		return m_rhedCloudSamlIssuer;
	}
	
	private void setRhedCloudSecurityRiskDetectionServiceUserArn (String arn) 
		throws StepException {
	
		if (arn == null) {
			String errMsg = "setRhedCloudSecurityRiskDetectionServiceUserArn " +
				"property is null. Can't continue.";
			throw new StepException(errMsg);
		}
		m_rhedCloudSecurityRiskDetectionServiceUserArn = arn;
	}

	private String getRhedCloudSecurityRiskDetectionServiceUserArn() {
		return m_rhedCloudSecurityRiskDetectionServiceUserArn;
	}
	
	private void setRhedCloudAwsAccountServiceUserArn (String arn) 
		throws StepException {
	
		if (arn == null) {
			String errMsg = "setRhedCloudAwsAccountServiceUserArn " +
				"property is null. Can't continue.";
			throw new StepException(errMsg);
		}
		m_rhedCloudAwsAccountServiceUserArn = arn;
	}

	private String getRhedCloudAwsAccountServiceUserArn() {
		return m_rhedCloudAwsAccountServiceUserArn;
	}
	
	private void setRhedCloudMaintenanceOperatorRoleArn (String arn) 
		throws StepException {
	
		if (arn == null) {
			String errMsg = "rhedCloudMaintenanceOperatorRoleArn " +
				"property is null. Can't continue.";
			throw new StepException(errMsg);
		}
		m_rhedCloudMaintenanceOperatorRoleArn = arn;
	}

	private String getRhedCloudMaintenanceOperatorRoleArn() {
		return m_rhedCloudMaintenanceOperatorRoleArn;
	}
	
	private void setStackName (String name) 
		throws StepException {
	
		if (name == null) {
			String errMsg = "stackName property is null. Can't continue.";
			throw new StepException(errMsg);
		}
		m_stackName = name;
	}

	private String getStackName() {
		return m_stackName;
	}
	
	private String getCloudFormationTemplateBody() throws StepException{

		String LOGTAG = getStepTag() + 
			"[CreateRsAccountCfnStack.getCloudFormationTemplateBody] ";
		String templateBody = null;
		
		if (getCloudFormationTemplateBodyUrl() != null) {
			try {
				URL url = new URL(getCloudFormationTemplateBodyUrl());
				templateBody = IOUtils.toString(url, TEMPLATE_BODY_ENCODING);
				return templateBody;
			}
			catch (IOException ioe) {
				String errMsg = "An error occurred reading the CloudFormation"
					+ " template body by URL. The exception is: " + 
					ioe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
		}
		else {
			String errMsg = "CloudFormation template body URL is null. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
	}
	
}