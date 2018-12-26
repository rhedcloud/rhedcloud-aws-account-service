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
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.lightweightdirectoryservices.v1_0.OrganizationalUnit;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProvisioning;
import edu.emory.moa.objects.resources.v1_0.OrganizationalUnitQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionRequisition;

/**
 * If this is a new account, provision an organization unit
 * for the new account in the Lightweight Directory Service (LDS).
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class CreateLdsOrganizationalUnit extends AbstractStep implements Step {
	
	private ProducerPool m_ldsServiceProducerPool = null;
	private String m_organizationalUnitDescriptionTemplate = null;
	private String m_organizationalUnitDnTemplate = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[CreateLdsOrganizationalUnit.init] ";
		
		logger.info(LOGTAG + "Getting custom step properties...");
		String organizationalUnitDescriptionTemplate = getProperties()
				.getProperty("organizationalUnitDescriptionTemplate", null);
		setOrganizationalUnitDescriptionTemplate(organizationalUnitDescriptionTemplate);
		logger.info(LOGTAG + "organizationalUnitDescriptionTemplate is: " + 
				getOrganizationalUnitDescriptionTemplate());
		
		String organizationalUnitDnTemplate = getProperties()
				.getProperty("organizationalUnitDnTemplate", null);
		setOrganizationalUnitDnTemplate(organizationalUnitDnTemplate);
		logger.info(LOGTAG + "organizationalUnitDnTemplate is: " + 
				getOrganizationalUnitDnTemplate());
		
		// This step needs to send messages to the LDS Service
		// to provision or deprovision the OU for the new account.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("LdsServiceProducerPool");
			setLdsServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			addResultProperty("errorMessage", errMsg);
			throw new StepException(errMsg);
		}
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[ExampleStep.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		// Get the generateNewAccount and the newAccountId properties
		// from previous steps to determine if a new account has been
		// provisioned
		String allocateNewAccount = 
			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
		String newAccountId = 
			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
		
		boolean allocatedNewAccount = Boolean.parseBoolean(allocateNewAccount);
		logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
		logger.info(LOGTAG + "newAccountId: " + newAccountId);
		
		// If allocatedNewAccount is true and newAccountId is not null, 
		// Send an AccountAlias.Create-Request to the AWS Account service.
		if (allocatedNewAccount && (newAccountId != null && newAccountId.equalsIgnoreCase("null") == false)) {
			logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " + 
				"is not null. Sending an OrganizationUnit.Create-Request to create an" +
				"acount alias.");
			
			// Get a configured OrganizationalUnit object from AppConfig.
			OrganizationalUnit ou = new OrganizationalUnit();
		    try {
		    	ou = (OrganizationalUnit)getAppConfig()
			    		.getObjectByType(ou.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Set the values of the OrganizationalUnit.
		    try {
		    	ou.addobjectClass("organizationalUnit");
		    	ou.addobjectClass("top");
		    	ou.setdescription(buildDescriptionValueFromTemplate(newAccountId));
		    	ou.setdistinguishedName(buildDnValueFromTemplate(newAccountId));
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "object. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the OrganizationalUnit.
		    try {
		    	logger.info(LOGTAG + "OrganizationalUnit is: " +
		    		ou.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the object " +
		  	    	  "to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
	  	    	throw new StepException(errMsg, xeoe);
		    }    
			
			// Get a producer from the pool
			RequestService rs = null;
			try {
				rs = (RequestService)getLdsServiceProducerPool()
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
				ou.create(rs);
				long createTime = System.currentTimeMillis() - createStartTime;
				logger.info(LOGTAG + "Created OrganizationalUnit  in "
					+ createTime + " ms.");
				addResultProperty("createdOrganizationalUnit", "true"); 
				addResultProperty("distinguishedName", ou.getdistinguishedName()); 
				
			}
			catch (EnterpriseObjectCreateException eoce) {
				String errMsg = "An error occurred creating the  " +
		    	  "OrganizationalUnit object. " +
		    	  "The exception is: " + eoce.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoce);
			}
			finally {
				// Release the producer back to the pool
				getLdsServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
			
		}
		
		// Otherwise, no new account was provisioned and an new organization
		// unit is not necessary.
		else {
			logger.info(LOGTAG + "allocatedNewAccount is false or there " +
				"is no newAccountId. There is no need to create a new " +
				"organizationalUnit.");
			addResultProperty("allocatedNewAccount", 
				Boolean.toString(allocatedNewAccount)); 
			addResultProperty("newAccountId", newAccountId); 
			addResultProperty("createdOrganizationalUnit", "not applicable");
					
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
			"[CreateLdsOrganizationalUnit.simulate] ";
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
			"[CreateLdsOrganizationalUnit.fail] ";
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
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[CreateLdsOrganizationalUnit.rollback] ";
		
		// Get the generateNewAccount and the newAccountId properties
		// from previous steps to determine if a new account has been
		// provisioned
		String createdOrganizationalUnit = 
			getResultProperty("createdOrganizationalUnit");
		boolean rollbackRequired = 
			Boolean.parseBoolean(createdOrganizationalUnit);
		String distinguishedName = 
			getResultProperty("distinguishedName");
		
		// If the step property createdOrganizationalUnit is true,
		// Query for the Organizational unit and then delete it.
		if (rollbackRequired) {
			// Query for the OrganizationalUnit by distinguished name.
			
			// Get a configured OrganizationalUnit object and query spec 
			// from AppConfig.
			OrganizationalUnit ou = new OrganizationalUnit();
			OrganizationalUnitQuerySpecification querySpec = 
				new OrganizationalUnitQuerySpecification();
		    try {
		    	ou = (OrganizationalUnit)getAppConfig()
			    		.getObjectByType(ou.getClass().getName());
		    	querySpec = (OrganizationalUnitQuerySpecification)getAppConfig()
		    			.getObjectByType(querySpec.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Set the values of the querySpec.
		    try {
		    	querySpec.setdistinguishedName(distinguishedName);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "object. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the query spec.
		    try {
		    	logger.info(LOGTAG + "query spec is: " +
		    		querySpec.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the object " +
		  	    	  "to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
	  	    	throw new StepException(errMsg, xeoe);
		    }    
			
			// Get a producer from the pool
			RequestService rs = null;
			try {
				rs = (RequestService)getLdsServiceProducerPool()
					.getExclusiveProducer();
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a producer " +
					"from the pool. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, jmse);
			}
		    
			List results = null;
			try { 
				long queryStartTime = System.currentTimeMillis();
				results = ou.query(querySpec, rs);
				long queryTime = System.currentTimeMillis() - queryStartTime;
				logger.info(LOGTAG + "Queried for OrganizationalUnit  in "
					+ queryTime + " ms. There are " + results.size() +
					" result(s)."); 
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred querying for the  " +
		    	  "VpnConnectionProfile object. " +
		    	  "The exception is: " + eoqe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoqe);
			}
			finally {
				// Release the producer back to the pool
				getLdsServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
			
			// If there is exactly one result delete it and set result
			// properties.
			if (results.size() == 1) {
				ou = (OrganizationalUnit)results.get(0);
				
			    // Log the state of the OrganizationalUnit.
			    try {
			    	logger.info(LOGTAG + "OrganizationalUnit is: " +
			    		ou.toXmlString());
			    }
			    catch (XmlEnterpriseObjectException xeoe) {
			    	String errMsg = "An error occurred serializing the object " +
			  	    	  "to XML. The exception is: " + xeoe.getMessage();
		  	    	logger.error(LOGTAG + errMsg);
		  	    	throw new StepException(errMsg, xeoe);
			    }    
				
				// Get a producer from the pool
				rs = null;
				try {
					rs = (RequestService)getLdsServiceProducerPool()
						.getExclusiveProducer();
				}
				catch (JMSException jmse) {
					String errMsg = "An error occurred getting a producer " +
						"from the pool. The exception is: " + jmse.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new StepException(errMsg, jmse);
				}
			    
				try { 
					long deleteStartTime = System.currentTimeMillis();
					ou.delete("Delete", rs);
					long deleteTime = System.currentTimeMillis() - deleteStartTime;
					logger.info(LOGTAG + "Deleted OrganizationalUnit in "
						+ deleteTime + " ms.");
					addResultProperty("deletedOrganizationalUnit", "true"); 
				}
				catch (EnterpriseObjectDeleteException eode) {
					String errMsg = "An error occurred deleting the  " +
			    	  "OrganizationalUnit object. " +
			    	  "The exception is: " + eode.getMessage();
			    	logger.error(LOGTAG + errMsg);
			    	throw new StepException(errMsg, eode);
				}
				finally {
					// Release the producer back to the pool
					getLdsServiceProducerPool()
						.releaseProducer((MessageProducer)rs);
				}
			}
			
			// Otherwise, log an error.
			else {
				String errMsg = "Invalid number of OrganizationalUnit objects"
					+ " returned. Got " + results.size() +
					", exected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
		}
		
		// No organizational unit was created, there is nothing to roll back.
		else {
			logger.info("No organizational unit was created. There is nothing "
				+ "to roll back.");
			addResultProperty("deletedOrganizationalUnit", "not applicable");
		}
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setLdsServiceProducerPool(ProducerPool pool) {
		m_ldsServiceProducerPool = pool;
	}
	
	private ProducerPool getLdsServiceProducerPool() {
		return m_ldsServiceProducerPool;
	}
	
	private void setOrganizationalUnitDescriptionTemplate (String template) throws 
		StepException {
	
		if (template == null) {
			String errMsg = "organizationalUnitDescriptionTemplate property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_organizationalUnitDescriptionTemplate = template;
	}

	private String getOrganizationalUnitDescriptionTemplate() {
		return m_organizationalUnitDescriptionTemplate;
	}
	
	private String buildDescriptionValueFromTemplate(String accountId) {
		String description = getOrganizationalUnitDescriptionTemplate()
			.replace("ACCOUNT_NUMBER", accountId);
		return description;
	}
	
	private void setOrganizationalUnitDnTemplate (String template) throws 
		StepException {
	
		if (template == null) {
			String errMsg = "organizationalUnitDnTemplate property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}

		m_organizationalUnitDnTemplate = template;
	}

	private String getOrganizationalUnitDnTemplate() {
		return m_organizationalUnitDnTemplate;
	}
	
	private String buildDnValueFromTemplate(String accountId) {
		String dn = getOrganizationalUnitDnTemplate()
			.replace("ACCOUNT_NUMBER", accountId);
		return dn;
	}
	
}
