/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * If this is a new account request, send a e-mail validation
 * query request to verify the e-mail distribution list for
 * this account is valid.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 17 August 2018
 **/
public class VerifyNewAccountAdminDistroList extends AbstractStep implements Step {
	
	private ProducerPool m_emailAddressValidationServiceProducerPool = null;
	private String m_accountSeriesPrefix = null;
	private String m_accountSequenceNumber = null;
	private int m_requestTimeoutInterval = 10000;
	//backwrdCompatibility to emory
	private String emailDistroListUserNamePrefix = "aws-";
	private String emailDistroListDomainName="@emory.edu";
	// TJ:11/03/2020 externalizing valid codes
    private List<String> validCodeList = new ArrayList<String>();
    private boolean isValidateEmail = true;

    
	public void init (String provisioningId, Properties props,
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[VerifyNewAccountAdminDistroList.init] ";

		// TJ:11/03/2020 externalizing valid codes (START)
	    String validateEmail = getProperties().getProperty("validateEmailAddress", "true");
	    isValidateEmail = Boolean.parseBoolean(validateEmail);
	    
        String validCodes = getProperties().getProperty("validCodes", "0,3,4");
        String[] validCodesArray = validCodes.split(",");

        for (int i = 0; i < validCodesArray.length; i++) {
            String code = validCodesArray[i];
            validCodeList.add(code.trim());
        }
		// TJ:11/03/2020 externalizing valid codes (END)

		// This step needs to send messages to the 
		// EmailAddressValidationService to validate e-mail
		// addresses.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("EmailAddressValidationServiceProducerPool");
			setEmailAddressValidationServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			if (isValidateEmail) {
				// An error occurred retrieving an object from AppConfig. Log it and
				// throw an exception.
				String errMsg = "An error occurred retrieving an object from " +
						"AppConfig. The exception is: " + ecoe.getMessage();
				logger.fatal(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
			else {
				logger.info(LOGTAG + " no EmailAddressValidationServiceProducerPool "
					+ "but validateEmailAddress is 'false' so processing will continue.");
			}
		}
		
		logger.info(LOGTAG + "Getting custom step properties...");
		String accountSeriesPrefix = getProperties()
				.getProperty("accountSeriesPrefix", null);
		setAccountSeriesPrefix(accountSeriesPrefix);
		logger.info(LOGTAG + "accountSeriesPrefix is: " + 
				getAccountSeriesPrefix());
		
		// requestTimeoutInterval is the time to wait for the
		// response to the request
		String timeout = getProperties().getProperty("requestTimeoutInterval",
			"10000");
		int requestTimeoutInterval = Integer.parseInt(timeout);
		setRequestTimeoutInterval(requestTimeoutInterval);
		logger.info(LOGTAG + "requestTimeoutInterval is: " + 
			getRequestTimeoutInterval());

		emailDistroListUserNamePrefix = getProperties().getProperty("emailDistroListUserNamePrefix", emailDistroListUserNamePrefix);
		logger.info(LOGTAG + "emailDistroListUserNamePrefix is: " + emailDistroListUserNamePrefix);
		emailDistroListDomainName=getProperties().getProperty("emailDistroListDomainName", emailDistroListDomainName);
		logger.info(LOGTAG + "emailDistroListDomainName is: " + emailDistroListDomainName);

		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[VerifyNewAccountAdminDistroList.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean isValid = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		boolean allocateNewAccount = false;
		if (step != null) {
			logger.info(LOGTAG + "Step DETERMINE_NEW_OR_EXISTING_ACCOUNT found.");
			String sAllocateNewAccount = getResultProperty(step, "allocateNewAccount");
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
		
		// Get the accountSequenceNumbner property from the
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
			setAccountSequenceNumber(accountSequenceNumber);
		}
		else {
			String errMsg = "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE not found. " +
				"Cannot determine account sequence number.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// If allocateNewAccount is true and the account sequence number is not null,
		// send an EmailAddressValidation.Query-Request to the EmailAddressValidation
		// service to validate the e-mail distribution list for the account.
		if (allocateNewAccount == true && accountSequenceNumber != null) {
			logger.info(LOGTAG + "allocateNewAccount is true and accountSequenceNumber " + 
				"is " + accountSequenceNumber + ". Sending an " +
				"EmailAccountValidation.Query-Request to determine if the " +
				"e-mail distribution list is valid to use for a new account.");
			
			// Get a configured EmailAddressValidation object and query spec from AppConfig.
			EmailAddressValidation eav = new EmailAddressValidation();
			EmailAddressValidationQuerySpecification eavqs = new
					EmailAddressValidationQuerySpecification();
		    try {
		    	eav = (EmailAddressValidation)getAppConfig()
			    		.getObjectByType(eav.getClass().getName());
		    	eavqs = (EmailAddressValidationQuerySpecification)getAppConfig()
			    		.getObjectByType(eavqs.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
			
		    // Build the account e-mail address to validate.
 			String accountEmailAddress = getAccountEmailAddress();
 			logger.info(LOGTAG + "accountEmailAddress is: " + accountEmailAddress);
 			addResultProperty("accountEmailAddress", accountEmailAddress);
 			addResultProperty("accountSeriesPrefix", getAccountSeriesPrefix());
 			addResultProperty("accountAlias", getAccountAlias());
		    
 			// TJ:11/03/2020: Only validate the email address if configured to do so (START)
 	        if (isValidateEmail) {
 			    // Set the values of the query spec.
 			    try {
 			    	eavqs.setEmailAddress(accountEmailAddress);
 			    }
 			    catch (EnterpriseFieldException efe) {
 			    	String errMsg = "An error occurred setting the values of the " +
 			  	    	  "query spec. The exception is: " + efe.getMessage();
 			  	    logger.error(LOGTAG + errMsg);
 			  	    throw new StepException(errMsg, efe);
 			    }
 			    
 			    // Log the state of the query spec.
 			    try {
 			    	logger.info(LOGTAG + "Query spec is: " + eavqs.toXmlString());
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
 					PointToPointProducer p2p = 
 						(PointToPointProducer)getEmailAddressValidationServiceProducerPool()
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
 					long queryStartTime = System.currentTimeMillis();
 					results = eav.query(eavqs, rs);
 					long queryTime = System.currentTimeMillis() - queryStartTime;
 					logger.info(LOGTAG + "Queried for EmailAddressValidation" +
 						"for e-mail address " + accountEmailAddress + " in "
 						+ queryTime + " ms. Returned " + results.size() + 
 						" result.");
 				}
 				catch (EnterpriseObjectQueryException eoqe) {
 					String errMsg = "An error occurred querying for the  " +
 			    	  "EmailAddressValidation object. " +
 			    	  "The exception is: " + eoqe.getMessage();
 			    	logger.error(LOGTAG + errMsg);
 			    	throw new StepException(errMsg, eoqe);
 				}
 				finally {
 					// Release the producer back to the pool
 					getEmailAddressValidationServiceProducerPool()
 						.releaseProducer((MessageProducer)rs);
 				}

 				logger.info(LOGTAG + "received " + results.size() + " result(s)");
 				if (results.size() == 1) {
 					EmailAddressValidation eavResult = 
 							(EmailAddressValidation)results.get(0);
 					String statusCode = eavResult.getStatusCode();
 					logger.info(LOGTAG + "statusCode=" + statusCode);
 					
 		 			// TJ:11/03/2020: get list of valid codes from AppConfig (START)
 					codeLoop: for (String code : validCodeList) {
 						if (statusCode.equalsIgnoreCase(code)) {
 	 						isValid = true;
 	 						logger.info(LOGTAG + "isValid is true");
 	 						addResultProperty("isValid", Boolean.toString(isValid));
 	 						break codeLoop;
 						}
 					}
 					if (!isValid) {
 						logger.info(LOGTAG + "isValid is false");
 						addResultProperty("isValid", Boolean.toString(isValid));
 					}
 		 			// TJ:11/03/2020: get list of valid codes from AppConfig (END)
 				}
 				else {
 					String errMsg = "Invalid number of results returned from " +
 						"EmailAddressValidation.Query-Request. " +
 						results.size() + " results returned. Expected exactly 1.";
 					logger.error(LOGTAG + errMsg);
 					throw new StepException(errMsg);
 				}
 	        }
 	        else {
 	        	// we're not validating the email address so set isValid to true
 	        	isValid = true;
				logger.info(LOGTAG + "NOT validating email address.  Setting isValid=true");
 	        }
 			// TJ:11/03/2020: Only validate the email address if configured to do so (END)
		}
		// If allocateNewAccount and accountSequenceNumber is false, log it and
		// add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to verify a new account distro list.");
			addResultProperty("allocateNewAccount", 
				Boolean.toString(allocateNewAccount));
			addResultProperty("accountSequenceNumber", 
				accountSequenceNumber);
			addResultProperty("isValid", "not applicable");
		}
		
		// Update the step.
		String stepResult = FAILURE_RESULT;
		if (allocateNewAccount == true && isValid == true) {
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
			"[VerifyNewAccountAdminDistroList.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
    	
		// Get the accountSequenceNumbner property from the
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
			setAccountSequenceNumber(accountSequenceNumber);
		}
		else {
			String errMsg = "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE not found. " +
				"Cannot determine account sequence number.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
    	
    	String accountEmailAddress = getAccountEmailAddress();
			logger.info(LOGTAG + "accountEmailAddress is: " + accountEmailAddress);
			addResultProperty("accountEmailAddress", accountEmailAddress);
		
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
			"[VerifyNewAccountAdminDistroList.fail] ";
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
			"[VerifyNewAccountAdminDistroList.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setEmailAddressValidationServiceProducerPool(ProducerPool pool) {
		m_emailAddressValidationServiceProducerPool = pool;
	}
	
	private ProducerPool getEmailAddressValidationServiceProducerPool() {
		return m_emailAddressValidationServiceProducerPool;
	}
	
	private void setAccountSeriesPrefix(String prefix) {
		m_accountSeriesPrefix = prefix;
	}
	
	private String getAccountSeriesPrefix() {
		return m_accountSeriesPrefix;
	}
	
	private String getAccountAlias() {
		String alias = emailDistroListUserNamePrefix + getAccountSeriesPrefix() + "-"
			+ getAccountSequenceNumber();
				
		return alias;
	}
	
	private String getAccountEmailAddress() {
		String emailAddress = getAccountAlias() + emailDistroListDomainName;
				
		return emailAddress;
	}
	
	private void setAccountSequenceNumber(String accountSequenceNumber) {
		m_accountSequenceNumber = accountSequenceNumber;
	}
	
	private String getAccountSequenceNumber() {
		return m_accountSequenceNumber;
	}
	
	private void setRequestTimeoutInterval(int i) {
		m_requestTimeoutInterval = i;
	}
	
	private int getRequestTimeoutInterval() {
		return m_requestTimeoutInterval;
	}
}
