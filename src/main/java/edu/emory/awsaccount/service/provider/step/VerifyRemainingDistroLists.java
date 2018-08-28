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
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;

/**
 * If this is a new account request, send a e-mail validation
 * query requests to count the remaining pre-provisioned
 * distribution lists. If fewer than the specified threshold
 * remain, create a ServiceNow Incident to alert the Messaging 
 * Team that it is time to provision more distribution lists.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 17 August 2018
 **/
public class VerifyRemainingDistroLists extends AbstractStep implements Step {
	
	private ProducerPool m_emailAddressValidationServiceProducerPool = null;
	private String m_accountSeriesPrefix = null;
	private String m_accountSequenceNumber = null;
	private int m_distroListAlertThreshold = 0;
	private boolean m_createIncidentOnAlert = false;
	private boolean m_notifyCentralAdminsOnAlert = false;
	private String m_incidentShortDescription = null;
	private String m_incidentDescription = null;
	private String m_incidentUrgency = null;
	private String m_incidentImpact = null;
	private String m_incidentBusinessService = null;
	private String m_incidentCategory = null;
	private String m_incidentSubCategory = null;
	private String m_incidentRecordType = null;
	private String m_incidentContactType = null;
	private String m_incidentCallerId = null;
	private String m_incidentCmdbCi = null;
	private String m_incidentAssignmentGroup = null;
	private String m_notificationType = null;
	private String m_notificationPriority = null;
	private String m_notificationSubject = null;
	private String m_notificationText = null;
	

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[VerifyRemainingDistroLists.init] ";
		
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
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		logger.info(LOGTAG + "Getting custom step properties...");
		String accountSeriesPrefix = getProperties()
				.getProperty("accountSeriesPrefix", null);
		setAccountSeriesPrefix(accountSeriesPrefix);
		logger.info(LOGTAG + "accountSeriesPrefix is: " + 
				getAccountSeriesPrefix());
		
		String distroListAlertThreshold = getProperties()
				.getProperty("distroListAlertThreshold", null);
		setDistroListAlertThreshold(distroListAlertThreshold);
		logger.info(LOGTAG + "distroListAlertThreshold is: " + 
				getDistroListAlertThreshold());
		
		String createIncidentOnAlert = getProperties()
				.getProperty("createIncidentOnAlert", null);
		setCreateIncidentOnAlert(createIncidentOnAlert);
		logger.info(LOGTAG + "createIncidentOnAlert is: " + 
				getCreateIncidentOnAlert());
		
		String notifyCentralAdminsOnAlert = getProperties()
				.getProperty("notifyCentralAdminsOnAlert", null);
		setNotifyCentralAdminsOnAlert(notifyCentralAdminsOnAlert);
		logger.info(LOGTAG + "createIncidentOnAlert is: " + 
				getNotifyCentralAdminsOnAlert());	
		
		String incidentShortDescription = getProperties()
				.getProperty("incidentShortDescription", null);
		setIncidentShortDescription(incidentShortDescription);
		logger.info(LOGTAG + "incidentShortDescription is: " + 
				getIncidentShortDescription());
		
		String incidentDescription = getProperties()
				.getProperty("incidentDescription", null);
		setIncidentDescription(incidentDescription);
		logger.info(LOGTAG + "incidentDescription is: " + 
				getIncidentDescription());
		
		String incidentUrgency = getProperties()
				.getProperty("incidentUrgency", null);
		setIncidentUrgency(incidentUrgency);
		logger.info(LOGTAG + "incidentUrgency is: " + 
				getIncidentUrgency());
		
		String incidentImpact = getProperties()
				.getProperty("incidentImpact", null);
		setIncidentImpact(incidentImpact);
		logger.info(LOGTAG + "incidentImpact is: " + 
				getIncidentImpact());
		
		String incidentBusinessService = getProperties()
				.getProperty("incidentBusinessService", null);
		setIncidentBusinessService(incidentBusinessService);
		logger.info(LOGTAG + "incidentBusinessService is: " + 
				getIncidentBusinessService());
		
		String incidentCategory = getProperties()
				.getProperty("incidentCategory", null);
		setIncidentCategory(incidentCategory);
		logger.info(LOGTAG + "incidentCategory is: " + 
				getIncidentCategory());
		
		String incidentSubCategory = getProperties()
				.getProperty("incidentSubCategory", null);
		setIncidentSubCategory(incidentSubCategory);
		logger.info(LOGTAG + "incidentSubCatetory is: " + 
				getIncidentSubCategory());
		
		String incidentRecordType = getProperties()
				.getProperty("incidentRecordType", null);
		setIncidentRecordType(incidentRecordType);
		logger.info(LOGTAG + "incidentRecordType is: " + 
				getIncidentRecordType());
		
		String incidentContactType = getProperties()
				.getProperty("incidentContactType", null);
		setIncidentContactType(incidentContactType);
		logger.info(LOGTAG + "incidentContactType is: " + 
				getIncidentContactType());
		
		String incidentCallerId = getProperties()
				.getProperty("incidentCallerId", null);
		setIncidentCallerId(incidentCallerId);
		logger.info(LOGTAG + "incidentCallerId is: " + 
				getIncidentCallerId());
		
		String incidentCmdbCi = getProperties()
				.getProperty("incidentCmdbCi", null);
		setIncidentCmdbCi(incidentCmdbCi);
		logger.info(LOGTAG + "incidentCmdbCi is: " + 
				getIncidentCmdbCi());
		
		String incidentAssignmentGroup = getProperties()
				.getProperty("incidentAssignmentGroup", null);
		setIncidentAssignmentGroup(incidentAssignmentGroup);
		logger.info(LOGTAG + "incidentAssignmentGroup is: " + 
				getIncidentAssignmentGroup());
		
		String notificationType = getProperties()
				.getProperty("notificationType", null);
		setNotificationType(notificationType);
		logger.info(LOGTAG + "notificationType is: " + 
				getNotificationType());
		
		String notificationPriority = getProperties()
				.getProperty("notificationPriority", null);
		setNotificationPriority(notificationPriority);
		logger.info(LOGTAG + "notificationPriority is: " + 
				getNotificationPriority());
		
		String notificationSubject = getProperties()
				.getProperty("notificationSubject", null);
		setNotificationSubject(notificationSubject);
		logger.info(LOGTAG + "notificationSubject is: " + 
				getNotificationSubject());
		
		String notificationText = getProperties()
				.getProperty("notificationText", null);
		setNotificationText(notificationText);
		logger.info(LOGTAG + "notificationText is: " + 
				getNotificationText());
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[VerifyRemainingDistroLists.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean isValid = false;
		
		// Return properties
		List<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		
		// Get the allocateNewAccount property from the
		// DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
		boolean allocateNewAccount = false;
		if (step != null) {
			logger.info(LOGTAG + "Step DETERMINE_NEW_OR_EXISTING_ACCOUNT found.");
			String sAllocateNewAccount = getResultProperty(step, "allocateNewAccount");
			allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
			props.add(buildProperty("allocateNewAccount", Boolean.toString(allocateNewAccount)));
			logger.info(LOGTAG + "Property allocateNewAccount from preceding " +
				"step is: " + allocateNewAccount);
		}
		else {
			String errMsg = "Step DETERMINE_NEW_OR_EXISTING_ACCOUNT found. " +
				"Cannot determine whether or not to authorize the new account " +
				"requestor.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Get the accountSequenceNumner property from the
		// DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE step.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		ProvisioningStep step2 = getProvisioningStepByType("DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE");
		String accountSequenceNumber = null;
		if (step2 != null) {
			logger.info(LOGTAG + "Step DETERMINE_NEW_ACCOUNT_SEQUENCE_VALUE found.");
			accountSequenceNumber = getResultProperty(step2, "accountSequenceNumber");
			props.add(buildProperty("accountSequenceNumber", accountSequenceNumber));
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
		// count the number of remaining valid e-mail addresses in the distro list 
		// series by sending an EmailAddressValidation.Query-Request to the 
		// EmailAddressValidation service for each e-mail address in the series until
		// one is invalid.
		if (allocateNewAccount == true && accountSequenceNumber != null) {
			logger.info(LOGTAG + "allocateNewAccount is true and accountSequenceNumber " + 
				"is " + accountSequenceNumber + ". Will count remaining distro lists.");
			
			boolean lessThanAlertThreshold = false;
			
			// Get the sequence value at which to start the queries.
			int sequenceStart = Integer.parseInt(accountSequenceNumber);
			int sequenceNumber = sequenceStart;
			boolean lastEmailAddressIsValid = true;
			
			while(lastEmailAddressIsValid == true) {
				String nextEmailAddress = getAccountSeriesPrefix() + "-" + 
					++sequenceNumber + "@emory.edu";
				lastEmailAddressIsValid = isValid(nextEmailAddress);
				logger.info(LOGTAG + "Distro list " + nextEmailAddress +
					" isValid: " + lastEmailAddressIsValid);
			}
			
			// Compute property values.
			int remainingValidDistroLists = (sequenceNumber - 1) - sequenceStart;
			if (remainingValidDistroLists < getDistroListAlertThreshold()) {
				lessThanAlertThreshold = true;
			}
			
			// Set properties
			props.add(buildProperty("remainingValidDistroLists", 
				Integer.toString(remainingValidDistroLists)));
			props.add(buildProperty("lessThanAlertThreshold", 
				Boolean.toString(lessThanAlertThreshold)));
						
			logger.info(LOGTAG + "There are " + remainingValidDistroLists +
				" remaining in the series.");
			logger.info(LOGTAG + "lessThanAlertThreshold: " +
					lessThanAlertThreshold);
			
			// If the remaining valid distro lists is less than the alert
			// threshold, create an incident in ServiceNow to request that
			// the messaging team add more and notify all central
			// administrators.
			Incident incident = null;
			if (lessThanAlertThreshold && getCreateIncidentOnAlert()) {
				logger.info(LOGTAG + "createIncidentOnAlert is true, " +
					"creating Incident in ServiceNow...");
				IncidentRequisition req = 
					buildIncidentRequisition(remainingValidDistroLists, 
						getAccountSeriesPrefix());
				try {
					incident = getVirtualPrivateCloudProvisioningProvider()
						.generateIncident(req);
				}
				catch (ProviderException pe) {
					String errMsg = "An error occurred generating an incident." +
						"The exception is: " + pe.getMessage();
					throw new StepException(errMsg, pe);
				}
				logger.info(LOGTAG + "Created incident " + incident.getNumber() +
						" in ServiceNow.");
			}
			if (lessThanAlertThreshold && getNotifyCentralAdminsOnAlert()) {
				logger.info(LOGTAG + "notifyCentralAdminsOnAlert is true, " +
						"notifying central administrators...");
				UserNotification notification = buildUserNotification(incident, 
					getAccountSeriesPrefix(), remainingValidDistroLists);
				try {
					int adminCount = getVirtualPrivateCloudProvisioningProvider()
							.notifyCentralAdministrators(notification);
					logger.info(LOGTAG + "Notified " + adminCount + 
							" central administrators.");
				}
				catch (ProviderException pe) {
					String errMsg = "An error occurred notifying central " +
						"administrators. The exception is: " + pe.getMessage();
					throw new StepException(errMsg, pe);
				}
			}
		}
		
		// If allocateNewAccount and accountSequenceNumber is false, log it and
		// add result props.
		else {
			logger.info(LOGTAG + "allocateNewAccount is false. " +
				"no need to verify a new account distro list.");
			props.add(buildProperty("allocateNewAccount", Boolean.toString(allocateNewAccount)));
			props.add(buildProperty("accountSequenceNumber", accountSequenceNumber));
			props.add(buildProperty("remainingValidDistroLists", "not applicable"));
			props.add(buildProperty("lessThanAlertThreshold", "not applicable"));
		}
		
		// Update the step.
		update(COMPLETED_STATUS, SUCCESS_RESULT, props);
		
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[VerifyRemainingDistroLists.simulate] ";
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
			"[VerifyRemainingDistroLists.fail] ";
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
			"[VerifyRemainingDistroLists.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT, getResultProperties());
		
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
	
	private void setDistroListAlertThreshold (String threshold) throws 
		StepException {
		
		if (threshold == null) {
			String errMsg = "distroListAlertThreshold property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		
		m_distroListAlertThreshold = Integer.parseInt(threshold);
	}
	
	private int getDistroListAlertThreshold() {
		return m_distroListAlertThreshold;
	}
	
	private void setAccountSeriesPrefix(String prefix) throws 
		StepException {
		
		if (prefix == null) {
			String errMsg = "accountSeriesPrefix property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		
		m_accountSeriesPrefix = prefix;
	}

	private String getAccountSeriesPrefix() {
		return m_accountSeriesPrefix;
	}
	
	private void setCreateIncidentOnAlert(String createIncidentOnAlert)  
		throws StepException {
		
		if (createIncidentOnAlert == null) {
			String errMsg = "createIncidentOnAlert property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_createIncidentOnAlert = Boolean.parseBoolean(createIncidentOnAlert);
	}

	private boolean getCreateIncidentOnAlert() {
		return m_createIncidentOnAlert;
	}
	
	private void setNotifyCentralAdminsOnAlert(String notifyCentralAdminsOnAlert)  
		throws StepException {
		
		if (notifyCentralAdminsOnAlert == null) {
			String errMsg = "notifyCentralAdminsOnAlert property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_notifyCentralAdminsOnAlert = Boolean.parseBoolean(notifyCentralAdminsOnAlert);
	}

	private boolean getNotifyCentralAdminsOnAlert() {
		return m_notifyCentralAdminsOnAlert;
	}
	
	private void setIncidentShortDescription(String incidentShortDescription)  
		throws StepException {
		
		if (incidentShortDescription == null) {
			String errMsg = "incidentShortDescription property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentShortDescription = incidentShortDescription;
	}

	private String getIncidentShortDescription() {
		return m_incidentShortDescription;
	}
	
	private void setIncidentDescription(String incidentDescription)  
		throws StepException {
		
		if (incidentDescription == null) {
			String errMsg = "incidentDescription property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentDescription = incidentDescription;
	}
	
	private String getIncidentDescription() {
		return m_incidentDescription;
	}
	
	private void setIncidentUrgency(String incidentUrgency)  
		throws StepException {
		
		if (incidentUrgency == null) {
			String errMsg = "incidentUrgency property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentUrgency = incidentUrgency;
	}
	
	private String getIncidentUrgency() {
		return m_incidentUrgency;
	}
	
	private void setIncidentImpact(String incidentImpact)  
		throws StepException {
		
		if (incidentImpact == null) {
			String errMsg = "incidentImpact property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentImpact = incidentImpact;
	}
	
	private String getIncidentImpact() {
		return m_incidentImpact;
	}
	
	private void setIncidentBusinessService(String incidentBusinessService)  
		throws StepException {
		
		if (incidentBusinessService == null) {
			String errMsg = "incidentBusinessService property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentBusinessService = incidentBusinessService;
	}
	
	private String getIncidentBusinessService() {
		return m_incidentBusinessService;
	}

	private void setIncidentCategory(String incidentCategory)  
		throws StepException {
		
		if (incidentCategory == null) {
			String errMsg = "incidentCategory property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentCategory = incidentCategory;
	}
	
	private String getIncidentCategory() {
		return m_incidentCategory;
	}
	
	private void setIncidentSubCategory(String incidentSubCategory)  
		throws StepException {
		
		if (incidentSubCategory == null) {
			String errMsg = "incidentSubCategory property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentSubCategory = incidentSubCategory;
	}	
	
	private String getIncidentSubCategory() {
		return m_incidentSubCategory;
	}
	
	private void setIncidentRecordType(String incidentRecordType)  
		throws StepException {
		
		if (incidentRecordType == null) {
			String errMsg = "incidentRecordType property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentRecordType = incidentRecordType;
	}
	
	private String getIncidentRecordType() {
		return m_incidentRecordType;
	}
	
	private void setIncidentContactType(String incidentContactType)  
		throws StepException {
		
		if (incidentContactType == null) {
			String errMsg = "incidentContactType property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentContactType = incidentContactType;
	}
	
	private String getIncidentContactType() {
		return m_incidentContactType;
	}
	
	private void setIncidentCallerId(String incidentCallerId)  
		throws StepException {
		
		if (incidentCallerId == null) {
			String errMsg = "incidentCallerId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentCallerId = incidentCallerId;
	}
	
	private String getIncidentCallerId() {
		return m_incidentCallerId;
	}
	
	private void setIncidentCmdbCi(String incidentCmdbCi)  
		throws StepException {
		
		if (incidentCmdbCi == null) {
			String errMsg = "incidentCmdbCi property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentCmdbCi = incidentCmdbCi;
	}
	
	private String getIncidentCmdbCi() {
		return m_incidentCmdbCi;
	}
	
	private void setIncidentAssignmentGroup(String incidentAssignmentGroup)  
		throws StepException {
		
		if (incidentAssignmentGroup == null) {
			String errMsg = "incidentAssignmentGroup property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_incidentAssignmentGroup = incidentAssignmentGroup;
	}
	
	private String getIncidentAssignmentGroup() {
		return m_incidentAssignmentGroup;
	}
	
	private void setNotificationType(String notificationType)  
		throws StepException {
		
		if (notificationType == null) {
			String errMsg = "notificationType property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_notificationType = notificationType;
	}
	
	private String getNotificationType() {
		return m_notificationType;
	}
	
	private void setNotificationPriority(String notificationPriority)  
		throws StepException {
		
		if (notificationPriority == null) {
			String errMsg = "notificationPriority property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_notificationPriority = notificationPriority;
	}
	
	private String getNotificationPriority() {
		return m_notificationPriority;
	}
	
	private void setNotificationSubject(String notificationSubject)  
		throws StepException {
		
		if (notificationSubject == null) {
			String errMsg = "notificationSubject property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_notificationSubject = notificationSubject;
	}
	
	private String getNotificationSubject() {
		return m_notificationSubject;
	}
	
	private void setNotificationText(String notificationText)  
		throws StepException {
		
		if (notificationText == null) {
			String errMsg = "notificationText property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_notificationText = notificationText;
	}
	
	private String getNotificationText() {
		return m_notificationText;
	}
	
	private String getAccountEmailAddress() {
		String emailAddress = getAccountSeriesPrefix() + "-" 
			+ getAccountSequenceNumber() + "@emory.edu";
				
		return emailAddress;
	}
	
	private void setAccountSequenceNumber(String accountSequenceNumber) {
		m_accountSequenceNumber = accountSequenceNumber;
	}
	
	private String getAccountSequenceNumber() {
		return m_accountSequenceNumber;
	}
	
	private boolean isValid(String emailAddress) throws StepException {
		String LOGTAG = getStepTag() + "[VerifyRemainingDistroLists.isValid] ";
		
		if (emailAddress == null) {
			String errMsg = "E-mail address is null. " + 
				"Can't validate a null e-mail address";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		boolean isValid = false;
		
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
		logger.info(LOGTAG + "nextAddress is: " + emailAddress);
	    
	    // Set the values of the query spec.
	    try {
	    	eavqs.setEmailAddress(emailAddress);
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
			rs = (RequestService)getEmailAddressValidationServiceProducerPool()
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
			results = eav.query(eavqs, rs);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for EmailAddressValidation" +
				"for e-mail address " + emailAddress + " in "
				+ queryTime + " ms. Returned " + results.size() + 
				" result.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "AccountProvisioningAuthorization object. " +
	    	  "The exception is: " + eoqe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoqe);
		}
		finally {
			// Release the producer back to the pool
			getEmailAddressValidationServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		if (results.size() == 1) {
			EmailAddressValidation eavResult = 
					(EmailAddressValidation)results.get(0);
			String statusCode = eavResult.getStatusCode();
			if (statusCode.equalsIgnoreCase("0")) {
				isValid = true;
				logger.info(LOGTAG + "isValid is true");
			}
			else {
				logger.info(LOGTAG + "isValid is false");
			}
		}
		else {
			String errMsg = "Invalid number of results returned from " +
				"AccountProvisioningAuthorization.Query-Request. " +
				results.size() + " results returned. Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		return isValid;
	}
	
	private IncidentRequisition buildIncidentRequisition(int remainingValidDistroLists,
		String accountPrefix)
		throws StepException {
		
		String LOGTAG = getStepTag() + 
			"[VerifyRemainingDistroLists.buildIncidentRequisition] ";
		
		// Get a configured IncidentRequisition from AppConfig
        IncidentRequisition req = new IncidentRequisition();
        try {
            req = (IncidentRequisition) getAppConfig().getObjectByType(req.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from AppConfig. " 
            	+ "The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }
		
		// Set the values of IncidentRequisition
        try {
	        req.setShortDescription(getIncidentShortDescription());
	        req.setDescription(getIncidentDescription());
	        req.getDescription().replaceAll("REMAINING_VALID_DISTRO_LIST_COUNT", 
	        	Integer.toString(remainingValidDistroLists));
	        req.getDescription().replaceAll("ACCOUNT_SERIES_PREFIX", 
		        	accountPrefix);
	        req.setUrgency(getIncidentUrgency());
	        req.setImpact(getIncidentImpact());
	        req.setBusinessService(getIncidentBusinessService());
	        req.setCategory(getIncidentCategory());
	        req.setSubCategory(getIncidentSubCategory());
	        req.setRecordType(getIncidentRecordType());
	        req.setContactType(getIncidentContactType());
	        req.setCallerId(getIncidentCallerId());
	        req.setCmdbCi(getIncidentCmdbCi());
	        req.setAssignmentGroup(getIncidentAssignmentGroup());
        }
        catch (EnterpriseFieldException efe) {
        	String errMsg = "An error occurred setting field values of an " +
        		"object. The exception is: " + efe.getMessage();
        	logger.error(LOGTAG + errMsg);
        	throw new StepException(errMsg, efe);
        }
		
		return req;
	}
	
	private UserNotification buildUserNotification(Incident incident, 
		String accountSeriesPrefix, int remainingValidDistroLists) throws
		StepException {
		
		String LOGTAG = getStepTag() + 
				"[VerifyRemainingDistroLists.buildUserNotification] ";
			
		// Get a configured UserNotification from AppConfig
        UserNotification notification = new UserNotification();
        try {
            notification = (UserNotification) getAppConfig()
            	.getObjectByType(notification.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from AppConfig. " 
            	+ "The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }
		
		// Set the values of UserNotification
        try {
	        notification.setType(getNotificationType());
	        notification.setPriority(getNotificationPriority());
	        notification.setSubject(getNotificationSubject());
	        notification.setText(getNotificationText());
	        notification.getText().replaceAll("ACCOUNT_SERIES_PREFIX", accountSeriesPrefix);
	        notification.getText().replaceAll("REMAINING_VALID_DISTRO_LIST_COUNT", 
	        	Integer.toString(remainingValidDistroLists));
	        notification.getText().replaceAll("INCIDENT_NUMBER", incident.getNumber());
	        notification.setRead("false");
	        notification.setCreateUser("AwsAccountService");
	        Datetime createDatetime = new Datetime("Create", System.currentTimeMillis());
	        notification.setCreateDatetime(createDatetime);
        }
        catch (EnterpriseFieldException efe) {
        	String errMsg = "An error occurred setting field values of an " +
        		"object. The exception is: " + efe.getMessage();
        	logger.error(LOGTAG + errMsg);
        	throw new StepException(errMsg, efe);
        }
			
		return notification;
	}
	
	
	
}
