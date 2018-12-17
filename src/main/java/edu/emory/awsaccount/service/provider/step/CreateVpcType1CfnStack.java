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
import java.util.ListIterator;
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
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.StackParameter;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If this is a new account request, build and send a
 * Stack.Generate-Request for the VPC Type 1 CloudFormation
 * Template.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 10 August 2018
 **/
public class CreateVpcType1CfnStack extends AbstractStep implements Step {
	
	private String m_cloudFormationTemplateUrl = null;
	private String m_cloudFormationTemplateBodyUrl = null;
	private String m_stackName = null;
	private int m_requestTimeoutInterval = 10000;
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private final static String TEMPLATE_BODY_ENCODING = "UTF-8";

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
		
		// stackName is the name to give the stack.
		String stackName = getProperties()
			.getProperty("stackName", null);
		setStackName(stackName);
		logger.info(LOGTAG + "stackName is: " + getStackName());
		
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
		
		// Get the VPN inside CIDR properties from the 
		// DETERMINE_VPC_CIDR step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step3 = 
			getProvisioningStepByType("DETERMINE_VPC_CIDR");
		String vpn1InsideTunnelCidr1 = null;
		String vpn1InsideTunnelCidr2 = null;
		String vpn1CustomerGatewayIp = null;
		String vpn2InsideTunnelCidr1 = null;
		String vpn2InsideTunnelCidr2 = null;
		String vpn2CustomerGatewayIp = null;
		if (step3 != null) {
			logger.info(LOGTAG + "Step COMPUTE_VPC_SUBNETS found.");
			
			vpn1InsideTunnelCidr1 = getResultProperty(step3, 
				"vpn1InsideTunnelCidr1");
			logger.info(LOGTAG + "Property vpn1InsideTunnelCidr1 from preceding " +
				"step is: " + vpn1InsideTunnelCidr1);
			props.add(buildProperty("vpn1InsideTunnelCidr1", vpn1InsideTunnelCidr1));
			
			vpn1InsideTunnelCidr2 = getResultProperty(step3, 
				"vpn1InsideTunnelCidr2");
			logger.info(LOGTAG + "Property vpn1InsideTunnelCidr2 from preceding " +
				"step is: " + vpn1InsideTunnelCidr2);
			props.add(buildProperty("vpn1InsideTunnelCidr2", vpn1InsideTunnelCidr2));
			
			vpn1CustomerGatewayIp = getResultProperty(step3, 
				"vpn1CustomerGatewayIp");
			logger.info(LOGTAG + "Property vpn1CustomerGatewayIp from preceding " +
				"step is: " + vpn1CustomerGatewayIp);
			props.add(buildProperty("vpn1CustomerGatewayIp", vpn1CustomerGatewayIp));
			
			vpn2InsideTunnelCidr1 = getResultProperty(step3, 
				"vpn2InsideTunnelCidr1");
			logger.info(LOGTAG + "Property vpn2InsideTunnelCidr1 from preceding " +
				"step is: " + vpn2InsideTunnelCidr1);
			props.add(buildProperty("vpn2InsideTunnelCidr1", vpn2InsideTunnelCidr1));
			
			vpn2InsideTunnelCidr2 = getResultProperty(step3, 
				"vpn2InsideTunnelCidr2");
			logger.info(LOGTAG + "Property vpn2InsideTunnelCidr2 from preceding " +
				"step is: " + vpn2InsideTunnelCidr2);
			props.add(buildProperty("vpn2InsideTunnelCidr2",vpn2InsideTunnelCidr2));
			
			vpn2CustomerGatewayIp = getResultProperty(step3, 
				"vpn2CustomerGatewayIp");
			logger.info(LOGTAG + "Property vpn2CustomerGatewayIp from preceding " +
				"step is: " + vpn2CustomerGatewayIp);
			props.add(buildProperty("vpn2CustomerGatewayIp", vpn2CustomerGatewayIp));
		}
		else {
			String errMsg = "Step DETERMINE_VPC_CIDR not " +
				"found. Cannot determine account sequence number.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Get the vpcNetwork property from the COMPUTE_VPC_SUBNETS step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step4 = 
			getProvisioningStepByType("COMPUTE_VPC_SUBNETS");
		String vpcNetwork = null;
		String mgmt1Subnet = null;
		String mgmt2Subnet = null;
		String public1Subnet = null;
		String public2Subnet = null;
		String private1Subnet = null;
		String private2Subnet = null;
		if (step4 != null) {
			logger.info(LOGTAG + "Step COMPUTE_VPC_SUBNETS found.");
			
			vpcNetwork = getResultProperty(step4, 
				"vpcNetwork");
			logger.info(LOGTAG + "Property vpcNetwork from preceding " +
				"step is: " + vpcNetwork);
			props.add(buildProperty("vpcNetwork", vpcNetwork));
			
			mgmt1Subnet = getResultProperty(step4, 
				"mgmt1Subnet");
			logger.info(LOGTAG + "Property mgmt1Subnet from preceding " +
				"step is: " + mgmt1Subnet);
			props.add(buildProperty("mgmt1Subnet", mgmt1Subnet));
			
			mgmt2Subnet = getResultProperty(step4, 
				"mgmt2Subnet");
			logger.info(LOGTAG + "Property mgmt2Subnet from preceding " +
				"step is: " + mgmt2Subnet);
			props.add(buildProperty("mgmt2Subnet", mgmt2Subnet));
			
			public1Subnet = getResultProperty(step4, 
				"public1Subnet");
			logger.info(LOGTAG + "Property public1Subnet from preceding " +
				"step is: " + public1Subnet);
			props.add(buildProperty("public1Subnet", public1Subnet));
			
			public2Subnet = getResultProperty(step4, 
				"public2Subnet");
			logger.info(LOGTAG + "Property public2Subnet from preceding " +
				"step is: " + public2Subnet);
			props.add(buildProperty("public2Subnet", public2Subnet));
			
			private1Subnet = getResultProperty(step4, 
				"private1Subnet");
			logger.info(LOGTAG + "Property private1Subnet from preceding " +
				"step is: " + private1Subnet);
			props.add(buildProperty("private1Subnet", private1Subnet));
			
			private2Subnet = getResultProperty(step4, 
				"private2Subnet");
			logger.info(LOGTAG + "Property private2Subnet from preceding " +
				"step is: " + private2Subnet);
			props.add(buildProperty("private2Subnet", private2Subnet));
		}
		else {
			String errMsg = "Step COMPUTE_VPC_SUBNETS not " +
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
		    	req.setDescription("RHEDcloud AWS CloudFormation template for type 1 vpc-level structures and policies");
		    
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
		    	
		    	// Parameter 1 - VpcCidr
		    	StackParameter parameter1 = req.newStackParameter();
		    	parameter1.setKey("VpcCidr");
		    	parameter1.setValue(vpcNetwork);
		    	req.addStackParameter(parameter1);
		    	
		    	// Parameter 2 - ManagementSubnet1Cidr
		    	StackParameter parameter2 = req.newStackParameter();
		    	parameter2.setKey("ManagementSubnet1Cidr");
		    	parameter2.setValue(mgmt1Subnet);
		    	req.addStackParameter(parameter2);
		    	
		    	// Parameter 3 - ManagementSubnet2Cidr
		    	StackParameter parameter3 = req.newStackParameter();
		    	parameter3.setKey("ManagementSubnet2Cidr");
		    	parameter3.setValue(mgmt2Subnet);
		    	req.addStackParameter(parameter3);
		        
		        // Parameter 4 - PublicSubnet1Cidr
		    	StackParameter parameter4 = req.newStackParameter();
		    	parameter4.setKey("PublicSubnet1Cidr");
		    	parameter4.setValue(public1Subnet);
		    	req.addStackParameter(parameter4);
		    	
		    	// Parameter 5 - PublicSubnet2Cidr
		    	StackParameter parameter5 = req.newStackParameter();
		    	parameter5.setKey("PublicSubnet2Cidr");
		    	parameter5.setValue(public2Subnet);
		    	req.addStackParameter(parameter5);
		    	
		        // Parameter 6 - PrivateSubnet1Cidr
		    	StackParameter parameter6 = req.newStackParameter();
		    	parameter6.setKey("PrivateSubnet1Cidr");
		    	parameter6.setValue(private1Subnet);
		    	req.addStackParameter(parameter6);
		    	
		    	// Parameter 7 - PrivateSubnet2Cidr
		    	StackParameter parameter7 = req.newStackParameter();
		    	parameter7.setKey("PrivateSubnet2Cidr");
		    	parameter7.setValue(private2Subnet);
		    	req.addStackParameter(parameter7);		    	
		    	
		        // Parameter 8 - RHEDcloudVpn1InsideTunnelCidr1
		    	StackParameter parameter8 = req.newStackParameter();
		    	parameter8.setKey("RHEDcloudVpn1InsideTunnelCidr1");
		    	parameter8.setValue(vpn1InsideTunnelCidr1);
		    	req.addStackParameter(parameter8);
		    	
		    	// Parameter 9 - RHEDcloudVpn1InsideTunnelCidr2
		    	StackParameter parameter9 = req.newStackParameter();
		    	parameter9.setKey("RHEDcloudVpn1InsideTunnelCidr2");
		    	parameter9.setValue(vpn1InsideTunnelCidr2);
		    	req.addStackParameter(parameter9);
		    	
		        // Parameter 10 - RHEDcloudVpn2InsideTunnelCidr1
		    	StackParameter parameter10 = req.newStackParameter();
		    	parameter10.setKey("RHEDcloudVpn2InsideTunnelCidr1");
		    	parameter10.setValue(vpn2InsideTunnelCidr1);
		    	req.addStackParameter(parameter10);
		    	
		    	// Parameter 11 - RHEDcloudVpn2InsideTunnelCidr2
		    	StackParameter parameter11 = req.newStackParameter();
		    	parameter11.setKey("RHEDcloudVpn2InsideTunnelCidr2");
		    	parameter11.setValue(vpn2InsideTunnelCidr2);
		    	req.addStackParameter(parameter11);
		    	
		    	// Parameter 12 - RHEDcloud1CustomerGatewayIp
		    	StackParameter parameter12 = req.newStackParameter();
		    	parameter12.setKey("RHEDcloud1CustomerGatewayIp");
		    	parameter12.setValue(vpn1CustomerGatewayIp);
		    	req.addStackParameter(parameter12);
		    	
		    	// Parameter 13 - RHEDcloud2CustomerGatewayIp
		    	StackParameter parameter13 = req.newStackParameter();
		    	parameter13.setKey("RHEDcloud2CustomerGatewayIp");
		    	parameter13.setValue(vpn2InsideTunnelCidr2);
		    	req.addStackParameter(parameter11);
		    	
		    	// Log out all parameters.
		    	List<StackParameter> params = req.getStackParameter();
		    	ListIterator<StackParameter> spi = params.listIterator();
		    	while (spi.hasNext()) {
		    		StackParameter param = (StackParameter)spi.next();
		    		logger.info(LOGTAG + "StackParameter " + param.getKey()
		    			+ ": " + param.getValue());
		    	}
		    	
		    	// Add capabilities
		    	String cap1 = "CAPABILITY_IAM";
		    	String cap2 = "CAPABILITY_NAMED_IAM";
		    	req.addCapability(cap1);
		    	req.addCapability(cap2);
		    	
		    	// Log out all capabilities and add them to the
		    	// step properties.
		    	List<String> capabilities = req.getCapability();
		    	ListIterator<String> ci = capabilities.listIterator();
		    	while (ci.hasNext()) {
		    		String capability = (String)ci.next();
		    		logger.info(LOGTAG + "Capability: " + capability);
		    	}
		    	
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