/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2020 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.*;
import com.amazonaws.services.support.AWSSupportClient;
import com.amazonaws.services.support.AWSSupportClientBuilder;
import com.amazonaws.services.support.model.CreateCaseRequest;
import com.amazonaws.services.support.model.CreateCaseResult;
import com.amazonaws.services.support.model.DescribeCasesRequest;
import com.amazonaws.services.support.model.DescribeCasesResult;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.DirectoryPerson;
import edu.emory.moa.objects.resources.v1_0.DirectoryPersonQuerySpecification;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;


public class CreateCaseForEnterpriseSupport extends AbstractStep implements Step {

    private final static String IN_PROGRESS = "IN_PROGRESS";
    private final static String SUCCEEDED = "SUCCEEDED";
    private final static String FAILED = "FAILED";
    private String m_accessKey = null;
    private String m_secretKey = null;
    private String m_caseServiceCode = null;
    private String m_caseCategoryCode = null;
    private String m_caseLanguage = null;
    private String m_caseCcEmailAddresses = null;
    private String m_caseCommunicationBody = null;
    private String m_caseSubject = null;
    private String m_caseSeverityCode = null;
    private ProducerPool m_directoryServiceProducerPool = null;
    
    private AWSSupportClient m_awsSupportClient = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[CreateCaseForEnterpriseSupport.init] ";

        // Get custom step properties.
        logger.info(LOGTAG + "Getting custom step properties...");

        String accessKey = getProperties().getProperty("accessKey", null);
        setAccessKey(accessKey);
        logger.info(LOGTAG + "accessKey is: " + getAccessKey());

        String secretKey = getProperties().getProperty("secretKey", null);
        setSecretKey(secretKey);
        logger.info(LOGTAG + "secretKey is: present");
        
        String caseServiceCode = getProperties().getProperty("caseServiceCode", null);
        setCaseServiceCode(caseServiceCode);
        logger.info(LOGTAG + "caseServiceCode is: " + getCaseServiceCode());
        
        String caseCategoryCode = getProperties().getProperty("caseCategoryCode", null);
        setCaseCategoryCode(caseCategoryCode);
        logger.info(LOGTAG + "caseCategoryCode is: " + getCaseCategoryCode());
        
        String caseLanguage = getProperties().getProperty("caseLanguage", null);
        setCaseLanguage(caseLanguage);
        logger.info(LOGTAG + "caseLanguage is: " + getCaseLanguage());
        
        String caseCcEmailAddresses = getProperties().getProperty("caseCcEmailAddresses", null);
        setCaseCcEmailAddresses(caseCcEmailAddresses);
        logger.info(LOGTAG + "caseCcEmailAddresses is: " + getCaseCcEmailAddresses());
        
        String caseCommunicationBody = getProperties().getProperty("caseCommunicationBody", null);
        setCaseCommunicationBody(caseCommunicationBody);
        logger.info(LOGTAG + "caseCommunicationBody is: " + getCaseCommunicationBody());
        
        String caseSubject = getProperties().getProperty("caseSubject", null);
        setCaseSubject(caseSubject);
        logger.info(LOGTAG + "caseSubject is: " + caseSubject);
        
        String caseSeverityCode = getProperties().getProperty("caseSeverityCode", null);
        setCaseSeverityCode(caseSeverityCode);
        logger.info(LOGTAG + "caseSeverityCode is: " + getCaseSeverityCode());

        // Set the AWS account credentials
        BasicAWSCredentials awsCredentials = 
        	new BasicAWSCredentials(accessKey, secretKey);

        // Instantiate an AWS client builder
        AWSSupportClientBuilder builder = AWSSupportClientBuilder.standard()
        	.withCredentials(new AWSStaticCredentialsProvider(awsCredentials));
        builder.setRegion("us-east-1");

        // Initialize the AWS client
        logger.info("Initializing AWS support client...");
        AWSSupportClient client = (AWSSupportClient)builder.build();
        logger.info("AWS support client initialized.");

        // Perform a test query
        DescribeCasesRequest request = new DescribeCasesRequest();
        DescribeCasesResult result = client.describeCases(request);
        logger.info(LOGTAG + "Describe cases result: " + result.toString());

        // Set the client
        setAwsSupportClient(client);
        
        // This step needs to send messages to the DirectoryService
        // to look up people to get e-mail addresses
        ProducerPool p2p2 = null;
        try {
            p2p2 = (ProducerPool) getAppConfig().getObject("DirectoryServiceProducerPool");
            setDirectoryServiceProducerPool(p2p2);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig. Log it and
            // throw an exception.
            String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateCaseForEnterpriseSupport.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
        
        // Get some properties from previous steps.
 		String allocateNewAccount = 
 			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
 		String newAccountId = 
 			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
 		
 		boolean allocatedNewAccount = Boolean.parseBoolean(allocateNewAccount) ;
 		logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
 		logger.info(LOGTAG + "newAccountId: " + newAccountId);
 		
 		boolean createdSupportCase = false;
 		String caseId = null;
 		
 		// If allocatedNewAccount is true and newAccountId is not null, 
 		// Create a support case to add the account to the enterprise support plan.
 		if (allocatedNewAccount && (newAccountId != null && newAccountId.equalsIgnoreCase("not applicable") == false)) {
 			logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " + 
 				"is not null. Will create a support case.");
    
			VirtualPrivateCloudProvisioning vpcp = getVirtualPrivateCloudProvisioning();
			VirtualPrivateCloudRequisition vpcr = vpcp.getVirtualPrivateCloudRequisition();
			String ownerId = vpcr.getAccountOwnerUserId();
			String requestorId = vpcr.getAuthenticatedRequestorUserId();
			String ownerEmail = getEmailForUserId(ownerId);
			String requestorEmail = getEmailForUserId(requestorId);

			CreateCaseRequest request = new CreateCaseRequest();
			request.setServiceCode(getCaseServiceCode());
			request.setCategoryCode(getCaseCategoryCode());
			request.setLanguage(getCaseLanguage());
			
			List<String> caseCcEmailAddresses = buildCcEmailAddresses(ownerEmail, requestorEmail);
			request.setCcEmailAddresses(caseCcEmailAddresses);
			String communicationBody = replaceAccountNumber(getCaseCommunicationBody(), newAccountId);
			request.setCommunicationBody(communicationBody);
			String caseSubject = replaceAccountNumber(getCaseSubject(), newAccountId);
			request.setSubject(caseSubject);
			request.setSeverityCode(getCaseSeverityCode());
			
			logger.info(LOGTAG + "Built the request: " + request.toString());
			
			// Send the request.
			try {
				logger.info(LOGTAG + "Sending the case create request...");
				long createStartTime = System.currentTimeMillis();
				CreateCaseResult result = getAwsSupportClient().createCase(request);
				long createTime = System.currentTimeMillis() - createStartTime;
				caseId = result.getCaseId();
				logger.info(LOGTAG + "received response to case create request in " +
					createTime + " ms. Case ID is: " + caseId);
				createdSupportCase = true;
				addResultProperty("caseId", "not applicable");
			}
			catch (Exception e) {
				String errMsg = "An error occurred creating the case. " +
					"The exception is: " + e.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, e);
			}
 		}
 		// This is not a new account, so no support request is needed.
 		else {
 			logger.info(LOGTAG + "This is not a new account, no support " +
 				"case is necessary.");
 			addResultProperty("caseId", "not applicable");
 		}
			
        String stepResult = FAILURE_RESULT;
        if (allocatedNewAccount == true && createdSupportCase == true) {
            stepResult = SUCCESS_RESULT;
        } 
        if (allocatedNewAccount == false && createdSupportCase == false) {
            stepResult = SUCCESS_RESULT;
        } 
      
        addResultProperty("createdStupportCase", Boolean.toString(createdSupportCase));
        update(COMPLETED_STATUS, stepResult);
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

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
        String LOGTAG = getStepTag() + "[GenerateNewAccount.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        // Update the step.
        update(COMPLETED_STATUS, FAILURE_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    public void rollback() throws StepException {

        super.rollback();

        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[GenerateNewAccount.rollback] ";

        logger.info(LOGTAG + "Rollback called, nothing to roll back.");

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private AWSSupportClient getAwsSupportClient() {
        return m_awsSupportClient;
    }

    private void setAwsSupportClient(AWSSupportClient client) {
        m_awsSupportClient = client;
    }

    private String getAccessKey() {
        return m_accessKey;
    }

    private void setAccessKey(String accessKey) throws
            StepException {

        if (accessKey == null) {
            String errMsg = "accessKey property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_accessKey = accessKey;
    }

    private String getSecretKey() {
        return m_secretKey;
    }

    private void setSecretKey(String secretKey) throws
            StepException {

        if (secretKey == null) {
            String errMsg = "secretKey property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_secretKey = secretKey;
    }
    
    private String getCaseServiceCode() {
        return m_caseServiceCode;
    }

    private void setCaseServiceCode(String caseServiceCode) throws
            StepException {

        if (caseServiceCode == null) {
            String errMsg = "caseServiceCode property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_caseServiceCode = caseServiceCode;
    }
    
    private String getCaseCategoryCode() {
        return m_caseCategoryCode;
    }

    private void setCaseCategoryCode(String caseCategoryCode) throws
            StepException {

        if (caseCategoryCode == null) {
            String errMsg = "caseCategoryCode property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_caseCategoryCode = caseCategoryCode;
    }

    private String getCaseLanguage() {
        return m_caseLanguage;
    }

    private void setCaseLanguage(String caseLanguage) throws
            StepException {

        if (caseLanguage == null) {
            String errMsg = "caseLanuage property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_caseLanguage = caseLanguage;
    }

    private String getCaseCcEmailAddresses() {
        return m_caseCcEmailAddresses;
    }

    private void setCaseCcEmailAddresses(String caseCcEmailAddresses) throws
            StepException {

        if (caseCcEmailAddresses == null) {
            String errMsg = "caseCcEmailAddresses property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_caseCcEmailAddresses = caseCcEmailAddresses;
    }
  
    private String getCaseCommunicationBody() {
        return m_caseCommunicationBody;
    }

    private void setCaseCommunicationBody(String caseCommunicationBody) throws
            StepException {

        if (caseCommunicationBody == null) {
            String errMsg = "caseCommunicationBody property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_caseCommunicationBody = caseCommunicationBody;
    }
    
    private String getCaseSubject() {
        return m_caseSubject;
    }

    private void setCaseSubject(String caseSubject) throws
            StepException {

        if (caseSubject == null) {
            String errMsg = "caseSubject property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_caseSubject = caseSubject;
    }
    
    private String getCaseSeverityCode() {
        return m_caseSeverityCode;
    }

    private void setCaseSeverityCode(String caseSeverityCode) throws
            StepException {

        if (caseSeverityCode == null) {
            String errMsg = "caseSeverityCode property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_caseSeverityCode = caseSeverityCode;
    }
    
    private void setDirectoryServiceProducerPool(ProducerPool pool) {
        m_directoryServiceProducerPool = pool;
    }

    private ProducerPool getDirectoryServiceProducerPool() {
        return m_directoryServiceProducerPool;
    }
    
    private List<String> buildCcEmailAddresses(String ownerEmail, String requestorEmail)
    	throws StepException {
    	
    	String LOGTAG = "[CreateCaseForEnterpriseSupport.buildCcEmailAddress] ";
    	
    	List<String> ccEmailAddresses = new ArrayList();
    	
    	if (getCaseCcEmailAddresses() != null && !getCaseCcEmailAddresses().equals("")) {
    		String[] emailAddresses = getCaseCcEmailAddresses().split("\\s*,\\s*");
    		for (int i=0; i < emailAddresses.length; i++) {
    			logger.info(LOGTAG + "Adding email address: " + emailAddresses[i]);
    			ccEmailAddresses.add(emailAddresses[i]);
    		}
    	}
    	
    	logger.info(LOGTAG + "Adding ownerEmail: " + ownerEmail);
    	ccEmailAddresses.add(ownerEmail);
    	logger.info(LOGTAG + "Adding requestorEmail: " + requestorEmail);
    	ccEmailAddresses.add(requestorEmail);
    	
    	logger.info(LOGTAG + "CcEmailAddresses is: " + ccEmailAddresses.toString());
    	
    	return ccEmailAddresses;
    }
    
    private String getEmailForUserId(String userId) throws StepException {
    	DirectoryPerson dp = directoryPersonQuery(userId);
    	String emailAddress = dp.getEmail().getEmailAddress();
    	return emailAddress;
    }
    
    private DirectoryPerson directoryPersonQuery(String userId) throws StepException {

    	String LOGTAG = "[CreateCaseForEnterpriseSupport.directoryPersonQuery] ";
    	
        // Query the DirectoryService service for the user's
        // DirectoryPerson object.

        // Get a configured DirectoryPerson and
        // DirectoryPersonQuerySpecification from AppConfig
        DirectoryPerson directoryPerson = new DirectoryPerson();
        DirectoryPersonQuerySpecification querySpec = new DirectoryPersonQuerySpecification();
        try {
            directoryPerson = (DirectoryPerson) getAppConfig().getObjectByType(directoryPerson.getClass().getName());
            querySpec = (DirectoryPersonQuerySpecification) getAppConfig().getObjectByType(querySpec.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }

        // Set the values of the querySpec.
        try {
            querySpec.setKey(userId);
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the " + "query specification object. The exception is: "
                    + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Get a RequestService to use for this transaction.
        RequestService rs = null;
        try {
            rs = (RequestService) getDirectoryServiceProducerPool().getExclusiveProducer();
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service to use " + "in this transaction. The exception is: "
                    + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, jmse);
        }
        // Query for the DirectoryPerson.
        List directoryPersonList = null;
        try {
            long startTime = System.currentTimeMillis();
            directoryPersonList = directoryPerson.query(querySpec, rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Queried for DirectoryPerson for " + "userId " + userId + " in " + time + " ms. Returned "
                    + directoryPersonList.size() + " user(s) in the role.");
        } catch (EnterpriseObjectQueryException eoqe) {
            String errMsg = "An error occurred querying for the DirectoryPerson objects The exception is: " + eoqe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoqe);
        }
        // In any case, release the producer back to the pool.
        finally {
            getDirectoryServiceProducerPool().releaseProducer((PointToPointProducer) rs);
        }

        if (directoryPersonList.size() == 0) {
            String errMsg = "Inappropriate number of DirectoryPerson " + "results. Expected 1 got " + directoryPersonList.size() + ".";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        DirectoryPerson dp = (DirectoryPerson) directoryPersonList.get(0);
        return dp;
    }
    
    private String replaceAccountNumber(String text, String accountNumber) {
    	String result = text.replaceAll("ACCOUNT_NUMBER", accountNumber);
        return result;
    }
    
}
