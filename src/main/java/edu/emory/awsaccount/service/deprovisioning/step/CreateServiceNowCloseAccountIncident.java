package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.awsaccount.service.provider.ProviderException;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.jms.JMSException;

public class CreateServiceNowCloseAccountIncident extends AbstractStep implements Step {
    private static final String LOGTAG_NAME = "CreateServiceNowCloseAccountIncident";
    private String shortDescription;
    private String longDescription;
    private String urgency;
    private String impact;
    private String businessService;
    private String category;
    private String subcategory;
    private String recordType;
    private String contactType;
    private String cmdbCi;
    private String incidentRequisitionCallerId;
    private String assignmentGroup;
    private ProducerPool m_awsAccountServiceProducerPool = null;

    @Override
    public void init(String deprovisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider adp) throws StepException {
        super.init(deprovisioningId, props, aConfig, adp);

        String LOGTAG = createLogTag("init");

        String shortDescription = getProperties().getProperty("shortDescription", null);
        logger.info(LOGTAG + "shortDescription is: " + shortDescription);
        setShortDescription(shortDescription);

        String longDescription = getProperties().getProperty("longDescription", null);
        logger.info(LOGTAG + "longDescription is: " + longDescription);
        setLongDescription(longDescription);

        String urgency = getProperties().getProperty("urgency", null);
        logger.info(LOGTAG + "urgency is: " + urgency);
        setUrgency(urgency);

        String impact = getProperties().getProperty("impact", null);
        logger.info(LOGTAG + "impact is: " + impact);
        setImpact(impact);

        String businessService = getProperties().getProperty("businessService", null);
        logger.info(LOGTAG + "businessService is: " + businessService);
        setBusinessService(businessService);

        String category = getProperties().getProperty("category", null);
        logger.info(LOGTAG + "category is: " + category);
        setCategory(category);

        String subcategory = getProperties().getProperty("subcategory", null);
        logger.info(LOGTAG + "subcategory is: " + subcategory);
        setSubscategory(subcategory);

        String recordType = getProperties().getProperty("recordType", null);
        logger.info(LOGTAG + "recordType is: " + recordType);
        setRecordType(recordType);

        String contactType = getProperties().getProperty("contactType", null);
        logger.info(LOGTAG + "contactType is: " + contactType);
        setContactType(contactType);

        String cmdbCi = getProperties().getProperty("cmdbCi", null);
        logger.info(LOGTAG + "cmdbCi is: " + cmdbCi);
        setCmbdCi(cmdbCi);

        String incidentRequisitionCallerId = getProperties().getProperty("incidentRequisitionCallerId", null);
        logger.info(LOGTAG + "incidentRequisitionCallerId is: " + incidentRequisitionCallerId);
        setIncidentRequisitionCallerId(incidentRequisitionCallerId);

        String assignmentGroup = getProperties().getProperty("assignmentGroup", null);
        logger.info(LOGTAG + "assignmentGroup is: " + assignmentGroup);
        setAssignmentGroup(assignmentGroup);
        
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
        
    }

    private void setAssignmentGroup(String assignmentGroup) throws StepException {
        if (assignmentGroup == null) throw new StepException("assignmentGroup cannot be null");
        this.assignmentGroup = assignmentGroup;
    }

    private void setIncidentRequisitionCallerId(String id) throws StepException {
        if (id == null) throw new StepException("incidentRequisitionCallerId cannot be null");
        this.incidentRequisitionCallerId = id;
    }

    private void setCmbdCi(String cmdbCi) throws StepException {
        if (cmdbCi == null) throw new StepException("cmdbCi cannot be null");
        this.cmdbCi = cmdbCi;
    }

    private void setContactType(String contactType) throws StepException {
        if (contactType == null) throw new StepException("contactType cannot be null");
        this.contactType = contactType;
    }

    private void setRecordType(String recordType) throws StepException {
        if (recordType == null) throw new StepException("recordType cannot be null");
        this.recordType = recordType;
    }

    private void setSubscategory(String subcategory) throws StepException {
        if (subcategory == null) throw new StepException("subcategory cannot be null");
        this.subcategory = subcategory;
    }

    private void setCategory(String category) throws StepException {
        if (category == null) throw new StepException("category cannot be null");
        this.category = category;
    }

    private void setBusinessService(String businessService) throws StepException {
        if (businessService == null) throw new StepException("businessService cannot be null");
        this.businessService = businessService;
    }

    private void setImpact(String impact) {
        this.impact = impact;
    }

    private void setUrgency(String urgency) throws StepException {
        if (urgency == null) throw new StepException("urgency cannot be null");
        this.urgency = urgency;
    }

    private void setLongDescription(String longDescription) throws StepException {
        if (longDescription == null) throw new StepException("longDescription cannot be null");
        this.longDescription = longDescription;
    }

    private void setShortDescription(String shortDescription) throws StepException {
        if (shortDescription == null) throw new StepException("shortDescription cannot be null");
        this.shortDescription = shortDescription;
    }

    @Override
    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();

        String LOGTAG = createLogTag("simulate");
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("isAuthorized", "true");

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("run");
        logger.info(LOGTAG + "Begin running the step.");

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        logger.info(LOGTAG + "accountId is: " + accountId);

        List<Property> props = new ArrayList<Property>();
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        /* begin business logic */
        
        // Get the AccountName
        Account account = accountQuery(accountId);
        String accountName = "NOT AVAILABLE";
        if (account != null) {
        	accountName = account.getAccountName();
        }

        logger.info(LOGTAG + "Preparing to send incident request");
        IncidentRequisition requisition = null;

        try {
            requisition = (IncidentRequisition) getAppConfig().getObjectByType(IncidentRequisition.class.getName());

            requisition.setShortDescription(getShortDescription(accountId, accountName));
            requisition.setDescription(getLongDescription(accountId, accountName));
            requisition.setUrgency(this.urgency);
            requisition.setImpact(this.impact);
            requisition.setBusinessService(this.businessService);
            requisition.setCategory(this.category);
            requisition.setSubCategory(this.subcategory);
            requisition.setRecordType(this.recordType);
            requisition.setContactType(this.contactType);
            requisition.setCmdbCi(this.cmdbCi);
            requisition.setCallerId(this.incidentRequisitionCallerId);
            requisition.setAssignmentGroup(this.assignmentGroup);

            Incident incident = getAccountDeprovisioningProvider().generateIncident(requisition);
            String incidentNumber = incident.getNumber();

            logger.info(LOGTAG + "incidentNumber is: " + incidentNumber);
            addResultProperty("incidentNumber", incidentNumber);
        } catch (EnterpriseConfigurationObjectException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } catch (ProviderException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        } catch (EnterpriseFieldException error) {
            String message = error.getMessage();
            logger.error(LOGTAG + message);
            throw new StepException(message, error);
        }

        /* end business logic */

        update(COMPLETED_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step completed in " + time + "ms.");

        return props;
    }

    private String getLongDescription(String accountId, String accountName) {
    	String lDesc = null;
        lDesc = this.longDescription.replace("ACCOUNT_ID", accountId);
        lDesc = lDesc.replace("ACCOUNT_NAME", accountName);
        return lDesc;
    }

    private String getShortDescription(String accountId, String accountName) {
    	String sDesc = null;
    	sDesc = this.shortDescription.replace("ACCOUNT_ID", accountId);
    	sDesc = sDesc.replace("ACCOUNT_NAME", accountName);
    	return sDesc;
    }

    @Override
    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("fail");
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

        update(COMPLETED_STATUS, FAILURE_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        return getResultProperties();
    }

    @Override
    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = createLogTag("rollback");

        logger.info(LOGTAG + "Rollback called, but this step has nothing to " +
                "roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private String createLogTag(String method) {
        return getStepTag() + "[" + LOGTAG_NAME + "." + method + "] ";
    }
    
    private Account accountQuery(String accountId) throws StepException {
    	
    	String LOGTAG = "[CreateServiceNowCloseAccountIncident.accountQuery] ";
    	
    	// Query for the account
		// Get a configured account object and account query spec
		// from AppConfig.
		Account account = new Account();
		AccountQuerySpecification querySpec = new AccountQuerySpecification();
	    try {
	    	account = (Account)getAppConfig()
		    	.getObjectByType(account.getClass().getName());
	    	querySpec = (AccountQuerySpecification)getAppConfig()
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
	    	querySpec.setAccountId(accountId);
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
			results = account.query(querySpec, rs);
			long createTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for Account in " + createTime +
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
		
		if (results.size() == 1) {
			Account acc = (Account)results.get(0);
			return acc;
		}
		else return null;
    	
    }
    
	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}
	
	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}
    
}
