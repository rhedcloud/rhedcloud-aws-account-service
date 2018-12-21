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
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Result;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloud;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.EmailAddress;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * If this is a new account request, create account metadata
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 30 August 2018
 **/
public class CreateVpcMetadata extends AbstractStep implements Step {
	
	private ProducerPool m_awsAccountServiceProducerPool = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[CreateVpcMetadata.init] ";
		
		// This step needs to send messages to the AWS account service
		// to create account metadata.
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
		String LOGTAG = getStepTag() + "[CreateAccountMetadata.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean vpcMetadataCreated = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get some properties from previous steps.
		String accountId = 
			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
		if (accountId == null || accountId.equalsIgnoreCase("null")) {
			accountId = getVirtualPrivateCloudProvisioning()
				.getVirtualPrivateCloudRequisition()
				.getAccountId();
			if (accountId == null || accountId.equals("")) {
				String errMsg = "No account number for the new VPC " +
					"can be found. Can't continue.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
		}
		String vpcId = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "VpcId");
		String region = getVirtualPrivateCloudProvisioning()
				.getVirtualPrivateCloudRequisition()
				.getRegion();
		String vpcType = getVirtualPrivateCloudProvisioning()
				.getVirtualPrivateCloudRequisition()
				.getType();
		String vpcCidr = getStepPropertyValue("COMPUTE_VPC_SUBNETS",
			"vpcNetwork"); 
		String vpnConnectionProfileId = getStepPropertyValue("DETERMINE_VPC_CIDR",
			"vpnConnectionProfileId");
		
		// Get the VirtualPrivateCloudRequisition
		VirtualPrivateCloudRequisition req = 
			getVirtualPrivateCloudProvisioning()
			.getVirtualPrivateCloudRequisition();
			
		// Get a configured VPC object from AppConfig.
		VirtualPrivateCloud vpc = new VirtualPrivateCloud();
	    try {
	    	vpc = (VirtualPrivateCloud)getAppConfig()
		    	.getObjectByType(vpc.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
	    
	    // Set the values of the VPC.
	    try {
	    	vpc.setAccountId(accountId);
	    	vpc.setVpcId(vpcId);
	    	vpc.setRegion(region);
	    	vpc.setType(vpcType);
	    	vpc.setCidr(vpcCidr);
	    	vpc.setVpnConnectionProfileId(vpnConnectionProfileId);
	    	vpc.setPurpose(req.getPurpose());
	    	vpc.setCreateUser(req.getAuthenticatedRequestorUserId());
	    	Datetime createDatetime = new Datetime("Create", 
	    			System.currentTimeMillis());
	    	vpc.setCreateDatetime(createDatetime);
	    }
	    catch (EnterpriseFieldException efe) {
	    	String errMsg = "An error occurred setting the values of the " +
	  	    	  "query spec. The exception is: " + efe.getMessage();
	  	    logger.error(LOGTAG + errMsg);
	  	    throw new StepException(errMsg, efe);
	    }
	    
	    // Log the state of the account.
	    try {
	    	logger.info(LOGTAG + "VPC to create is: " + vpc.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the query spec " +
	  	    	  "to XML. The exception is: " + xeoe.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new StepException(errMsg, xeoe);
	    }    
		
		// Get a producer from the pool
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool()
				.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
				"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}
	    
		try { 
			long createStartTime = System.currentTimeMillis();
			vpc.create(rs);
			long createTime = System.currentTimeMillis() - createStartTime;
			logger.info(LOGTAG + "Create Account in " + createTime +
				" ms.");
			vpcMetadataCreated = true;
			addResultProperty("vpcMetadataCreated", 
				Boolean.toString(vpcMetadataCreated));
			addResultProperty("accountId", accountId);
			addResultProperty("vpcId", vpcId);
			addResultProperty("region", region);
			addResultProperty("vpcType", vpcType);
			addResultProperty("vpcCidr", vpcCidr);
			addResultProperty("vpnConnectionProfileId", 
				vpnConnectionProfileId);
		}
		catch (EnterpriseObjectCreateException eoce) {
			String errMsg = "An error occurred creating the object. " +
	    	  "The exception is: " + eoce.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoce);
		}
		finally {
			// Release the producer back to the pool
			getAwsAccountServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		// Update the step.
		update(COMPLETED_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Step run completed in " + time + "ms.");
		
		// Return the properties.
		return getResultProperties();
		
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[CreateVpcMetadata.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
    	addResultProperty("accountMetadataCreated", "true");
		
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
			"[CreateVpcMetadata.fail] ";
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
		String LOGTAG = getStepTag() + "[CreateVpcMetadata.rollback] ";
		logger.info(LOGTAG + "Rollback called, deleting account metadata.");
		
		// Get the result props
		List<Property> props = getResultProperties();
		
		// Get the VpcId
		String vpcId = getResultProperty("vpcId");
		
		// If the vpcId is not null, query for the VPC object
		// and then delete it.
		if (vpcId != null) {
		
			// Query for the VPC
			// Get a configured VPC object and account query spec
			// from AppConfig.
			VirtualPrivateCloud vpc = new VirtualPrivateCloud();
			VirtualPrivateCloudQuerySpecification querySpec = 
				new VirtualPrivateCloudQuerySpecification();
		    try {
		    	vpc = (VirtualPrivateCloud)getAppConfig()
			    	.getObjectByType(vpc.getClass().getName());
		    	querySpec = (VirtualPrivateCloudQuerySpecification)getAppConfig()
				    	.getObjectByType(querySpec.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Set the values of the query spec
		    try {
		    	querySpec.setVpcId(vpcId);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting a field value. " +
		    		"The exception is: " + efe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException();
		    }
		    
		    // Get a producer from the pool
 			RequestService rs = null;
 			try {
 				rs = (RequestService)getAwsAccountServiceProducerPool()
 					.getExclusiveProducer();
 			}
 			catch (JMSException jmse) {
 				String errMsg = "An error occurred getting a producer " +
 					"from the pool. The exception is: " + jmse.getMessage();
 				logger.error(LOGTAG + errMsg);
 				throw new StepException(errMsg, jmse);
 			}
 		    
 			// Query for the account metadata
 			List results = null;
 			try { 
 				long queryStartTime = System.currentTimeMillis();
 				results = vpc.query(querySpec, rs);
 				long createTime = System.currentTimeMillis() - queryStartTime;
 				logger.info(LOGTAG + "Queried for VPC in " + createTime +
 					" ms. Got " + results.size() + " result(s).");
 			}
 			catch (EnterpriseObjectQueryException eoqe) {
 				String errMsg = "An error occurred querying for the object. " +
 		    	  "The exception is: " + eoqe.getMessage();
 		    	logger.error(LOGTAG + errMsg);
 		    	throw new StepException(errMsg, eoqe);
 			}
 			finally {
 				// Release the producer back to the pool
 				getAwsAccountServiceProducerPool()
 					.releaseProducer((MessageProducer)rs);
 			}
 			
 			// If there is a result, delete the account metadata
 			if (results.size() > 0) {
 				vpc = (VirtualPrivateCloud)results.get(0);
 				
 				// Get a producer from the pool
 	 			rs = null;
 	 			try {
 	 				rs = (RequestService)getAwsAccountServiceProducerPool()
 	 					.getExclusiveProducer();
 	 			}
 	 			catch (JMSException jmse) {
 	 				String errMsg = "An error occurred getting a producer " +
 	 					"from the pool. The exception is: " + jmse.getMessage();
 	 				logger.error(LOGTAG + errMsg);
 	 				throw new StepException(errMsg, jmse);
 	 			}
 	 		    
 	 			// Delete the account metadata
 	 			try { 
 	 				long deleteStartTime = System.currentTimeMillis();
 	 				vpc.delete("Delete", rs);
 	 				long deleteTime = System.currentTimeMillis() - deleteStartTime;
 	 				logger.info(LOGTAG + "Deleted VPC in " + deleteTime +
 	 					" ms. Got " + results.size() + " result(s).");
 	 				addResultProperty("deletedVpcMetadataOnRollback", "true");
 	 			}
 	 			catch (EnterpriseObjectDeleteException eode) {
 	 				String errMsg = "An error occurred deleting the object. " +
 	 		    	  "The exception is: " + eode.getMessage();
 	 		    	logger.error(LOGTAG + errMsg);
 	 		    	throw new StepException(errMsg, eode);
 	 			}
 	 			finally {
 	 				// Release the producer back to the pool
 	 				getAwsAccountServiceProducerPool()
 	 					.releaseProducer((MessageProducer)rs);
 	 			}
 			}
		}
		// If vpcId is null, there is nothing to roll back.
		// Log it.
		else {
			logger.info(LOGTAG + "No VPC metadata was created by this " +
				"step, so there is nothing to roll back.");
			addResultProperty("deletedVpcMetadataOnRollback", "not applicable");
		}
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}
	
}
