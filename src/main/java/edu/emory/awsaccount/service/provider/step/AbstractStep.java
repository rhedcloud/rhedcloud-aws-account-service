/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider.step;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
// Java utilities
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.TimeZone;

// Log4j
import org.apache.log4j.Category;

// JDOM
import org.jdom.Document;
import org.jdom.Element;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

//AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudQuerySpecification;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 *  An abstract class from which all provisioning steps inherit. This class
 *  implements common behaviors, such as required instance variable 
 *  initialization, querying the AWS account service for its state and the
 *  ability to update the step in the AWS account service.
 *  
 *  Step-specific behaviors are implemented in implementations that extend this
 *  class and implement the Step interface.
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 *
 */
public abstract class AbstractStep {

	protected Category logger = OpenEaiObject.logger;
	private String m_provisioningId = null;
	private String m_stepId = null;
	private String m_type = null;
	private String m_description = null;
	private String m_status = null;
	private String m_result = null;
	private String m_executionType = null;
	private List<Property> m_resultProperties = new ArrayList<Property>();
	private String m_createUser = null;
	private Datetime m_createDatetime = null;
	private String m_lastUpdateUser = null;
	private Datetime m_lastUpdateDatetime = null;
	private RequestService m_awsAccountService = null;
	private String LOGTAG = "[AbstractStep] ";
	private String CREATE_USER = "AwsAccountService";
	private boolean m_skipStep = false;
	private boolean m_simulateStep = false;
	private boolean m_failStep = false;
	private VirtualPrivateCloudProvisioning m_vpcp = null;
	private VirtualPrivateCloudProvisioningProvider m_vpcpp = null;
	private AppConfig m_appConfig = null;
	protected final String IN_PROGRESS_STATUS = "in progress";
	protected final String COMPLETED_STATUS = "completed";
	protected final String PENDING_STATUS = "pending";
	protected final String ROLLBACK_STATUS = "rolled back";
	protected final String NO_RESULT = null;
	protected final String SUCCESS_RESULT = "success";
	protected final String FAILURE_RESULT = "failure";
	protected final String RUN_EXEC_TYPE = "executed";
	protected final String SIMULATED_EXEC_TYPE = "simulated";
	protected final String SKIPPED_EXEC_TYPE = "skipped";
	protected final String FAILURE_EXEC_TYPE = "failure";
	protected String m_stepTag = null;
	protected long m_executionStartTime = 0;
	protected long m_executionTime = 0;
	protected long m_executionEndTime = 0;
	protected Properties m_props = null;

	public void init (String provisioningId, Properties props, 
		AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
		throws StepException {
		
		String LOGTAG = "[AbstractStep.init] ";
		logger.info(LOGTAG + "Initializing...");
		
		// Set identification and control properties of the step.
		setAppConfig(aConfig);
		setProvisioningId(provisioningId);
		setStepId(props.getProperty("stepId"));
		setType(props.getProperty("type"));
		setDescription(props.getProperty("description"));
		setSkipStep(Boolean.valueOf(props.getProperty("skipStep", "false")));
		setSimulateStep(Boolean.valueOf(props.getProperty("simulateStep", "false")));
		setFailStep(Boolean.valueOf(props.getProperty("failStep", "false")));
		setVirtualPrivateCloudProvisioningProvider(vpcpp);
		setProperties(props);
		
		// Query for the provisioning object.
		queryForVpcpBaseline();
		
		// If the VPCP object is not null, look for the step.
		ProvisioningStep step = null;
		if (getVirtualPrivateCloudProvisioning() != null) {
			step = getProvisioningStepById(getStepId());
		
			// If the provisioning step is present, set the initial values of this
			// step from those of the provisioning step. 
			if (step != null) {
				setType(step.getType());
				setDescription(step.getDescription());
				setStatus(step.getStatus());
				setCreateUser(step.getCreateUser());
				setCreateDatetime(step.getCreateDatetime());
				if (step.getLastUpdateUser() != null) {
					setLastUpdateUser(step.getLastUpdateUser());
				}
				if (step.getLastUpdateDatetime() != null) {
					setLastUpdateDatetime(step.getLastUpdateDatetime());
				}
			}
			// Otherwise, set initial values.
			else {
				setType(props.getProperty("type"));
				setDescription(props.getProperty("description"));
				setStatus(PENDING_STATUS);
				setCreateUser(CREATE_USER);
				setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
			}
		}
		// The provisioning object for the specified id does not exist. This
		// is a fatal step error.
		else {
			String errMsg = "No VirtualPrivateCloudProvisioning object " +
				"found for ProvisioningId " + provisioningId + ". Can't "+
				"continue.";
			throw new StepException(errMsg);
		}
		
		// Set the step tag value.
		String stepTag = "[ProvisioningId " + getProvisioningId() + "][Step-"
				+ getStepId() + "] ";
		setStepTag(stepTag);
		
		logger.info(LOGTAG + "Initialization complete #######################");
	}
	
	public List<Property> execute() throws StepException {
		setExecutionStartTime();
		
		// Update the step to indicate it is in progress.
		update(IN_PROGRESS_STATUS, NO_RESULT);
		
		String LOGTAG = getStepTag() + 
			"[AbstractStep.execute] ";
		logger.info(LOGTAG + "Determining execution method.");
		
		// Determine if the step should be skipped, simulated, or failed.
		// If skipStep is true, log it skip it and return a property indicating
		// that the step was skipped.
		if (getSkipStep()) {
			logger.info(LOGTAG + "skipStep is true, skipping this step.");
			addResultProperty("stepExecutionMethod", "skipped");
			setExecutionTime();
			return getResultProperties();
		}
		
		// If simulateStep is true, log it and call the simulate method.
		if (getSimulateStep()) {
			logger.info(LOGTAG + "simulateStep is true, simulating this step.");
			List<Property> props = simulate();
			setExecutionTime();
			return props;
		}
		
		// If failStep is true, log it and call the fail method.
		if (getFailStep()) {
			logger.info(LOGTAG + "failStep is true, failing this step.");
			List<Property> props = fail();
			setExecutionTime();
			return props;
		}
		
		// Otherwise run the step logic.
		else {
			logger.info(LOGTAG + "Running the step.");
			List<Property> props = run();
			setExecutionTime();
			return props;
		}
	}
	
	protected abstract List<Property> simulate() throws StepException;
	
	protected abstract List<Property> run() throws StepException;
	
	protected abstract List<Property> fail() throws StepException;
	
	/**
	 * @param AppConfig, the AppConfig
	 * <P>
	 * This method sets the AppConfig
	 */
	private void setAppConfig(AppConfig appConfig) 
		throws StepException {
		
		if (appConfig == null) {
			String errMsg = "AppConfig is null. AppConfig is required.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		m_appConfig = appConfig;
	}

	/**
	 * @return AppConfig, the AppConfig
	 * <P>
	 * This method returns the value of the AppConfig
	 */
	protected AppConfig getAppConfig() {
		return m_appConfig;
	}
	
	/**
	 * @return String, the ProvisioningId
	 * <P>
	 * This method returns the value of the ProvisioningId
	 */
	private String getProvisioningId() {
		return m_provisioningId;
	}

	/**
	 * @param String, the ProvisioningId
	 * <P>
	 * This method sets the ProvisioningId
	 */
	private void setProvisioningId(String provisioningId) 
		throws StepException {
		
		if (provisioningId == null) {
			String errMsg = "ProvisioiningId is null. StepId is a required " +
				"property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_provisioningId = provisioningId;
	}

	/**
	 * @return String, the StepId
	 * <P>
	 * This method returns the value of the StepId
	 */
	public String getStepId() {
		return m_stepId;
	}
	
	/**
	 * @param String, the StepId
	 * <P>
	 * This method sets the StepId
	 */
	private void setStepId(String stepId) throws StepException {
		if (stepId == null) {
			String errMsg = "StepId is null. StepId is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		m_stepId = stepId;
	}
	
	/**
	 * @return String, the StepTag
	 * <P>
	 * This method returns the value of the StepTag
	 */
	public String getStepTag() {
		return m_stepTag;
	}
	
	/**
	 * @param String, the StepTag
	 * <P>
	 * This method sets the StepTag
	 */
	private void setStepTag(String stepTag) throws StepException {
		m_stepTag = stepTag;
	}

	/**
	 * @param boolean, the skipStep property
	 * <P>
	 * This method sets the skipStep property
	 */
	private void setSkipStep(boolean skipStep) {
		m_skipStep = skipStep;
	}

	/**
	 * @return boolean, the skipStep property
	 * <P>
	 * This method returns the value of the skipStep property
	 */
	protected boolean getSkipStep() {
		return m_skipStep;
	}
	
	/**
	 * @param boolean, the simluateStep property
	 * <P>
	 * This method sets the simulateStep property
	 */
	private void setSimulateStep(boolean simulateStep) {
		m_simulateStep = simulateStep;
	}

	/**
	 * @return boolean, the simulateStep property
	 * <P>
	 * This method returns the value of the simulateStep property
	 */
	protected boolean getSimulateStep() {
		return m_simulateStep;
	}
	
	/**
	 * @param boolean, the failStep property
	 * <P>
	 * This method sets the failStep property
	 */
	private void setFailStep(boolean failStep) {
		m_failStep = failStep;
	}

	/**
	 * @return boolean, the failStep property
	 * <P>
	 * This method returns the value of the failStep property
	 */
	private boolean getFailStep() {
		return m_failStep;
	}
	
	/**
	 * @param VirtualPrivateCloudProvisioningProvider,
	 * the VirtualPrivateCloudProvisioningProvider
	 * <P>
	 * This method sets the VirtualPrivateCloudProvisioningProvider
	 */
	private void setVirtualPrivateCloudProvisioningProvider
		(VirtualPrivateCloudProvisioningProvider vpcpp) {
		
		m_vpcpp = vpcpp;
	}

	/**
	 * @return VirtualPrivateCloudProvisioningProvider, 
	 * the VirtualPrivateCloudProvisioningProvider
	 * <P>
	 * This method returns the VirtualPrivateCloudProvisioningProvider
	 */
	protected VirtualPrivateCloudProvisioningProvider
		getVirtualPrivateCloudProvisioningProvider() {
		
		return m_vpcpp;
	}

	/**
	 * @param VirtualPrivateCloudProvisioning,
	 * the VirtualPrivateCloudProvisioning object containing state
	 * for this step
	 * <P>
	 * This method sets the VirtualPrivateCloudProvisioning object
	 */
	private void setVirtualPrivateCloudProvisioning
		(VirtualPrivateCloudProvisioning vpcp) {
		
		m_vpcp = vpcp;
	}

	/**
	 * @return VirtualPrivateCloudProvisioning, 
	 * the VirtualPrivateCloudProvisioning object containing state
	 * for this step.
	 * <P>
	 * This method returns the VirtualPrivateCloudProvisioning object
	 */
	protected VirtualPrivateCloudProvisioning
		getVirtualPrivateCloudProvisioning() {
		
		return m_vpcp;
	}
	
	/**
	 * @return String, the Type
	 * <P>
	 * This method returns the value of the step Type
	 */
	public String getType() {
		return m_type;
	}

	/**
	 * @param String, the Type
	 * <P>
	 * This method sets the step Type
	 */
	private void setType(String type) throws StepException {
		
		if (type == null) {
			String errMsg = "Type is null. Type is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_type = type;
	}
	
	/**
	 * @return String, the Description
	 * <P>
	 * This method returns the value of the step Description
	 */
	public String getDescription() {
		return m_description;
	}

	/**
	 * @param String, the Description
	 * <P>
	 * This method sets the step Description
	 */
	private void setDescription(String description) throws StepException {
		
		if (description == null) {
			String errMsg = "Description is null. It is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_description = description;
	}
	
	/**
	 * @return String, the Status
	 * <P>
	 * This method returns the value of the status Description
	 */
	public String getStatus() {
		return m_status;
	}

	/**
	 * @param String, the Status
	 * <P>
	 * This method sets the step Status
	 */
	private void setStatus(String status) throws StepException {
		
		if (status == null) {
			String errMsg = "Status is null. It is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_status = status;
	}
	
	/**
	 * @return Properties, the step properties
	 * <P>
	 * This method returns the value of the step properties
	 */
	public Properties getProperties() {
		return m_props;
	}

	/**
	 * @param Properties, the step properties
	 * <P>
	 * This method sets the step properties
	 */
	private void setProperties(Properties props)  {		
		m_props = props;
	}
	
	/**
	 * @return String, the Result
	 * <P>
	 * This method returns the value of the result
	 */
	public String getResult() {
		return m_result;
	}

	/**
	 * @param String, the result
	 * <P>
	 * This method sets the step result
	 */
	private void setResult(String result)  {		
		m_result = result;
	}
	
	/**
	 * @return String, the CreateUser
	 * <P>
	 * This method returns the value of the CreateUser
	 */
	private String getCreateUser() {
		return m_createUser;
	}

	/**
	 * @param String, the CreateUser
	 * <P>
	 * This method sets the step CreateUser
	 */
	private void setCreateUser(String createUser) throws StepException {
		
		if (createUser == null) {
			String errMsg = "CreateUser is null. It is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_createUser = createUser;
	}
	
	/**
	 * @return Datetime, the CreateDatetime
	 * <P>
	 * This method returns the value of the CreateDatetime
	 */
	private Datetime getCreateDatetime() {
		return m_createDatetime;
	}

	/**
	 * @param Datetime, the CreateDatetime
	 * <P>
	 * This method sets the step CreateDatetime
	 */
	private void setCreateDatetime(Datetime createDatetime) throws StepException {
		
		if (createDatetime == null) {
			String errMsg = "CreateDatetime is null. It is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_createDatetime = createDatetime;
	}
	
	/**
	 * @return String, the LastUpdateUser
	 * <P>
	 * This method returns the value of the LastUpdateUser
	 */
	private String getLastUpdateUser() {
		return m_lastUpdateUser;
	}

	/**
	 * @param String, the LastUpdateUser
	 * <P>
	 * This method sets the step LastUpdateUser
	 */
	private void setLastUpdateUser(String lastUpdateUser) throws StepException {
		
		if (lastUpdateUser == null) {
			String errMsg = "LastUpdateUser is null. It is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_lastUpdateUser = lastUpdateUser;
	}
	
	/**
	 * @return Datetime, the LastUpdateDatetime
	 * <P>
	 * This method returns the value of the LastUpdateDatetime
	 */
	private Datetime getLastUpdateDatetime() {
		return m_lastUpdateDatetime;
	}

	/**
	 * @param Datetime, the LastUpdateDatetime
	 * <P>
	 * This method sets the step LastUpdateDatetime
	 */
	private void setLastUpdateDatetime(Datetime lastUpdateDatetime) throws StepException {
		
		if (lastUpdateDatetime == null) {
			String errMsg = "LastUpdateDatetime is null. It is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_lastUpdateDatetime = lastUpdateDatetime;
	}
	
	
	protected ProvisioningStep getProvisioningStepById(String stepId) {
		
		ProvisioningStep pStep = null;
		VirtualPrivateCloudProvisioning vpcp = 
			getVirtualPrivateCloudProvisioning();
		List steps = vpcp.getProvisioningStep();
		ListIterator li = steps.listIterator();
		while (li.hasNext()) {
			ProvisioningStep step = (ProvisioningStep)li.next();
			if (step.getStepId().equals(stepId)) {
				pStep = step;
			}
		}
		return pStep;
	}
	
	protected ProvisioningStep getProvisioningStepByType(String stepType) {
		
		ProvisioningStep pStep = null;
		VirtualPrivateCloudProvisioning vpcp = 
			getVirtualPrivateCloudProvisioning();
		List steps = vpcp.getProvisioningStep();
		ListIterator li = steps.listIterator();
		while (li.hasNext()) {
			ProvisioningStep step = (ProvisioningStep)li.next();
			if (step.getType().equalsIgnoreCase(stepType)) {
				pStep = step;
			}
		}
		return pStep;
	}
	
/**	
	protected Property buildProperty(String key, String value) {
		Property prop = m_vpcp.newProvisioningStep().newProperty();
		try {
			prop.setKey(key);
			prop.setValue(value);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the field values " +
				"of a property object. The exception is: " +
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
		}
    	return prop;
	}
**/	
	
	
	protected void setResultProperties(List<Property> resultProps) {
		m_resultProperties = resultProps;
	}
	
	private void addResultProperty(Property prop) {
		m_resultProperties.add(prop);
	}
	
	public void addResultProperty(String key, String value) {
		
		String LOGTAG = getStepTag() + "[AbstractStep.addResultProperty] "; 
		
		if (getResultProperties() == null) {
			List<Property> resultProperties = new ArrayList<Property>();
			setResultProperties(resultProperties);
		}
	
//		Property newProp = buildProperty(key, value);	
		
		Property newProp = m_vpcp.newProvisioningStep().newProperty();
		try {
			newProp.setKey(key);
			newProp.setValue(value);
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the field values " +
				"of a property object. The exception is: " +
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
		}
		
		// If the list already contains a Property
		// with this key value, update its value.
		boolean replacedValue = false;
		List<Property> properties = getResultProperties();
		ListIterator li = properties.listIterator();
		while (li.hasNext()) {
			Property oldProp = (Property)li.next();
			if (oldProp.getKey().equalsIgnoreCase(key)) {
				try {
					oldProp.setValue(value);
					logger.debug(LOGTAG + "Found an existing property with " +
						"key " + key + ". Replaced its value with: " + value);
				}
				catch (EnterpriseFieldException efe) {
					
				}
				replacedValue = true;
			}
		}
		// Otherwise, add the new property.
		if (replacedValue == false) {
			properties.add(newProp);
			logger.debug(LOGTAG + "No existing property with " +
				"key " + key + ". Added property with value: " +
				value);
		}
		
	}
	
	public List<Property> getResultProperties() {
		return m_resultProperties;
	}
	
	protected String getResultProperty(String key) {
		String value = null;
		ListIterator li = m_resultProperties.listIterator();
		while (li.hasNext()) {
			Property prop = (Property)li.next();
			if (prop.getKey().equalsIgnoreCase(key)) {
				value = prop.getValue();
			}
		}
		return value;
	}
	
	protected String getResultProperty(ProvisioningStep step, String key) {
		String value = null;
		List<Property> resultProperties = step.getProperty();
		ListIterator li = resultProperties.listIterator();
		while (li.hasNext()) {
			Property prop = (Property)li.next();
			if (prop.getKey().equalsIgnoreCase(key)) {
				value = prop.getValue();
			}
		}
		return value;
	}
	
	protected void setExecutionStartTime() {
		
		String LOGTAG = getStepTag() + 
			"[AbstractStep.setExecutionStartTime] ";
		
		m_executionStartTime = System.currentTimeMillis();
		
		addResultProperty("startTime", Long.toString(getExecutionStartTime()));
	
		java.util.Date date = new java.util.Date(getExecutionStartTime());
		DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
		String formattedDate = format.format(date);
		
		addResultProperty("startTimeFormatted", formattedDate);
		
		logger.info(LOGTAG + "Set step startTime to " 
			+ getExecutionStartTime() + 
			" or: " + formattedDate);
	}
	
	protected long getExecutionStartTime() {
		return m_executionStartTime;
	}
	
	protected void setExecutionTime() {
		long currentTime = System.currentTimeMillis();
		m_executionTime = currentTime - getExecutionStartTime();
		logger.info(LOGTAG + "Setting execution time: " + m_executionTime + 
			" = " + currentTime + " - " + m_executionStartTime);
		
		addResultProperty("executionTime", Long.toString(getExecutionTime()));
		
		logger.info(LOGTAG + "Set step executionTime to " 
			+ Long.toString(m_executionTime)); 
	}
	
	protected long getExecutionTime() {
		logger.info(LOGTAG + "Returning execution time: " + m_executionTime);
		return m_executionTime;
	}
	
	protected void setEndTime() {
		m_executionEndTime = System.currentTimeMillis();
		
		addResultProperty("executionEndTime", Long.toString(m_executionEndTime));
			
		java.util.Date date = new java.util.Date(m_executionEndTime);
		DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
		String formattedDate = format.format(date);
		
		addResultProperty("executionEndTimeFormatted", formattedDate);
		
		logger.info(LOGTAG + "Set step executionEndTime to " 
			+ getExecutionEndTime() + "or: " + formattedDate);
	}
	
	protected long getExecutionEndTime() {
		return m_executionEndTime;
	}
	
	public void update(String status, String result) throws StepException {
		
		// Update the baseline state of the VPCP
		queryForVpcpBaseline();
		
		// If the current status is in progress, update the 
		// execution time. Note that we don't want to
		// update the execution on update for steps that
		// have already completed or are in any other status
		// that in progress.
		if (getStatus().equalsIgnoreCase(IN_PROGRESS_STATUS)) {
			
//			setExecutionTime();
		}
		
		// If the status is changing from in progress to anything else,
		// set the executionEndTime.
		if (getStatus().equalsIgnoreCase(IN_PROGRESS_STATUS) == true &&
			status.equalsIgnoreCase(IN_PROGRESS_STATUS) == false) {
			
			setExecutionTime();
			setEndTime();
		}
		
		
		// Update the fields of this step.
		setStatus(status);
		setResult(result);
		
		// Get the corresponding provisioning step.
		ProvisioningStep pStep = getProvisioningStepById(getStepId());
		
		// Update the step values.
		try {
			pStep.setStatus(getStatus());
			pStep.setStepResult(getResult());
			pStep.setProperty(getResultProperties());
			pStep.setLastUpdateUser("AwsAccountService");
			pStep.setLastUpdateDatetime(new Datetime("LastUpdate", System.currentTimeMillis()));
			pStep.setActualTime(Long.toString(getExecutionTime()));
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the field values " +
				"of the ProvisioningStep. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, efe);
		}
		
		// Perform the step update.
		try {
			getVirtualPrivateCloudProvisioningProvider()
				.update(getVirtualPrivateCloudProvisioning());
		}
		catch (ProviderException pe) {
			String errMsg = "An error occurred updating the VPCP object " +
				"with an updated ProvisioningStep. The exception is: " + 
				pe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, pe);
		}	
	}
	
	protected void rollback() throws StepException {
		String LOGTAG = "[AbstractStep.rollback] ";
		logger.info(LOGTAG + "Initializing common rollback logic...");
		logger.info(LOGTAG + "Querying for VPVP baseline...");
		queryForVpcpBaseline();
	}
	
	private void queryForVpcpBaseline() throws StepException {
		// Query for the VPCP object in the AWS Account Service.
		// Get a configured query spec from AppConfig
		VirtualPrivateCloudProvisioningQuerySpecification vpcpqs = new
			VirtualPrivateCloudProvisioningQuerySpecification();
	    try {
	    	vpcpqs = (VirtualPrivateCloudProvisioningQuerySpecification)getAppConfig()
		    		.getObjectByType(vpcpqs.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
		
	    // Set the values of the query spec.
	    try {
	    	vpcpqs.setProvisioningId(getProvisioningId());
	    }
	    catch (EnterpriseFieldException efe) {
	    	String errMsg = "An error occurred setting the values of the " +
	  	    	  "VPCP query spec. The exception is: " + efe.getMessage();
	  	    logger.error(LOGTAG + errMsg);
	  	    throw new StepException(errMsg, efe);
	    }
	    
	    // Log the state of the query spec.
	    try {
	    	logger.info(LOGTAG + "Query spec is: " + vpcpqs.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the query spec " +
	  	    	  "to XML. The exception is: " + xeoe.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new StepException(errMsg, xeoe);
	    }
	    
		List results = null;
		try { 
			results = getVirtualPrivateCloudProvisioningProvider()
				.query(vpcpqs);
		}
		catch (ProviderException pe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "current state of a VirtualPrivateCloudProvisioning object. " +
	    	  "The exception is: " + pe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, pe);
		}
		VirtualPrivateCloudProvisioning vpcp = 
			(VirtualPrivateCloudProvisioning)results.get(0);
		
		setVirtualPrivateCloudProvisioning(vpcp);
	}
	
	protected String getStepPropertyValue(String stepType, String key) 
		throws StepException {
		String LOGTAG = getStepTag() + "[AbstractStep.getStepPropertyValue] ";
		
		// Get the property value with the named step and key.
		logger.info(LOGTAG + "Getting propertyfrom preceding step...");
		ProvisioningStep step = getProvisioningStepByType(stepType);
		String value = null;
		if (step != null) {
			value = getResultProperty(step, key);
			addResultProperty(key, value);
			logger.info(LOGTAG + "Property " + key + " from preceding " +
				"step " + stepType  + "is: " + value);	
		}
		else {
			String errMsg = "Step " + stepType + " not found. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		return value;
	}
}
