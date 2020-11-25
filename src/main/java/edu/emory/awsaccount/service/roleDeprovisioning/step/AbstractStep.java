/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.RoleDeprovisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningStep;
import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
import org.apache.log4j.Category;
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.moa.XmlEnterpriseObjectException;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.TimeZone;

/**
 *  An abstract class from which all provisioning steps inherit. This class
 *  implements common behaviors, such as required instance variable
 *  initialization, querying the AWS Account Service for its state and the
 *  ability to update the step in the AWS Account Service.
 *
 *  Step-specific behaviors are implemented in implementations that extend this
 *  class and implement the Step interface.
 */
public abstract class AbstractStep {
    private static final String LOGTAG = "[AbstractStep] ";
    private static final String AWS_ACCOUNT_SERVICE_USER = "AwsAccountService";

    protected Category logger = OpenEaiObject.logger;

    private String provisioningId = null;
    private String m_stepId = null;
    private String m_type = null;
    private String m_description = null;
    private String m_status = null;
    private String m_result = null;
    private List<Property> m_resultProperties = new ArrayList<>();
    private String m_createUser = null;
    private Datetime m_createDatetime = null;
    private String m_lastUpdateUser = null;
    private Datetime m_lastUpdateDatetime = null;
    private boolean m_skipStep = false;
    private boolean m_simulateStep = false;
    private boolean m_failStep = false;
    private RoleDeprovisioning roleDeprovisioning = null;
    private RoleDeprovisioningProvider roleDeprovisioningProvider = null;
    private AppConfig m_appConfig = null;

    protected String m_stepTag = null;
    protected long m_executionStartTime = 0;
    protected long m_executionTime = 0;
    protected long m_executionEndTime = 0;
    protected Properties m_props = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider rpp)
        throws StepException {

        String LOGTAG = "[AbstractStep.init] ";
        logger.info(LOGTAG + "Initializing...");

        // Set identification and control properties of the step.
        setAppConfig(aConfig);
        setProvisioningId(provisioningId);
        setStepId(props.getProperty("stepId"));
        setType(props.getProperty("type"));
        setDescription(props.getProperty("description"));
        setSkipStep(Boolean.parseBoolean(props.getProperty("skipStep", "false")));
        setSimulateStep(Boolean.parseBoolean(props.getProperty("simulateStep", "false")));
        setFailStep(Boolean.parseBoolean(props.getProperty("failStep", "false")));
        setRoleDeprovisioningProvider(rpp);
        setProperties(props);

        // Query for the provisioning object.
        queryForRoleDeprovisioningBaseline();

        // If the RoleDeprovisioning object is not null, look for the step.
        RoleDeprovisioningStep step;
        if (getRoleDeprovisioning() != null) {
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
                setStatus(Step.STEP_STATUS_PENDING);
                setCreateUser(AWS_ACCOUNT_SERVICE_USER);
                setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
            }
        }
        // The RoleDeprovisioning object for the specified id does not exist. This is a fatal step error.
        else {
            String errMsg = "No RoleDeprovisioning object found for ProvisioningId " + provisioningId + ". Can't continue.";
            throw new StepException(errMsg);
        }

        // Set the step tag value.
        String stepTag = "[ProvisioningId " + getProvisioningId() + "][Step-" + getStepId() + "] ";
        setStepTag(stepTag);

        logger.info(LOGTAG + "Initialization complete #######################");
    }

    public List<Property> execute() throws StepException {
        setExecutionStartTime();

        // Update the step to indicate it is in progress.
        update(Step.STEP_STATUS_IN_PROGRESS, Step.STEP_RESULT_NONE);

        String LOGTAG = getStepTag() + "[AbstractStep.execute] ";
        logger.info(LOGTAG + "Determining execution method.");

        // Determine if the step should be skipped, simulated, or failed.
        // If skipStep is true, log it skip it and return a property indicating that the step was skipped.
        if (getSkipStep()) {
            logger.info(LOGTAG + "skipStep is true, skipping this step.");
            addResultProperty(Step.STEP_EXECUTION_METHOD_PROPERTY_KEY, Step.STEP_EXECUTION_METHOD_SKIPPED);
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

    private void setAppConfig(AppConfig appConfig) throws StepException {
        if (appConfig == null) {
            String errMsg = "AppConfig is null. AppConfig is required.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
        m_appConfig = appConfig;
    }
    protected AppConfig getAppConfig() { return m_appConfig; }

    private String getProvisioningId() { return provisioningId; }
    private void setProvisioningId(String provisioningId) throws StepException {
        if (provisioningId == null) {
            String errMsg = "ProvisioningId is null. StepId is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        this.provisioningId = provisioningId;
    }

    public String getStepId() { return m_stepId; }
    private void setStepId(String stepId) throws StepException {
        if (stepId == null) {
            String errMsg = "StepId is null. StepId is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
        m_stepId = stepId;
    }
    public String getStepTag() { return m_stepTag; }
    private void setStepTag(String stepTag) { m_stepTag = stepTag; }
    private void setSkipStep(boolean skipStep) { m_skipStep = skipStep; }
    protected boolean getSkipStep() { return m_skipStep; }
    private void setSimulateStep(boolean simulateStep) { m_simulateStep = simulateStep; }
    protected boolean getSimulateStep() { return m_simulateStep; }
    private void setFailStep(boolean failStep) { m_failStep = failStep; }
    private boolean getFailStep() { return m_failStep; }
    private void setRoleDeprovisioningProvider(RoleDeprovisioningProvider v) { roleDeprovisioningProvider = v; }
    protected RoleDeprovisioningProvider getRoleDeprovisioningProvider() { return roleDeprovisioningProvider; }
    private void setRoleDeprovisioning(RoleDeprovisioning v) { roleDeprovisioning = v; }
    protected RoleDeprovisioning getRoleDeprovisioning() { return roleDeprovisioning; }
    public String getType() { return m_type; }
    private void setType(String type) throws StepException {
        if (type == null) {
            String errMsg = "Type is null. Type is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_type = type;
    }
    public String getDescription() { return m_description; }
    private void setDescription(String description) throws StepException {
        if (description == null) {
            String errMsg = "Description is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_description = description;
    }
    public String getStatus() { return m_status; }
    private void setStatus(String status) throws StepException {
        if (status == null) {
            String errMsg = "Status is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_status = status;
    }
    public Properties getProperties() { return m_props; }
    private void setProperties(Properties props)  { m_props = props; }
    public String getResult() { return m_result; }
    private void setResult(String result)  { m_result = result; }
    private String getCreateUser() { return m_createUser; }
    private void setCreateUser(String createUser) throws StepException {
        if (createUser == null) {
            String errMsg = "CreateUser is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_createUser = createUser;
    }
    private Datetime getCreateDatetime() { return m_createDatetime; }
    private void setCreateDatetime(Datetime createDatetime) throws StepException {
        if (createDatetime == null) {
            String errMsg = "CreateDatetime is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_createDatetime = createDatetime;
    }
    private String getLastUpdateUser() { return m_lastUpdateUser; }
    private void setLastUpdateUser(String lastUpdateUser) throws StepException {
        if (lastUpdateUser == null) {
            String errMsg = "LastUpdateUser is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_lastUpdateUser = lastUpdateUser;
    }
    private Datetime getLastUpdateDatetime() { return m_lastUpdateDatetime; }
    private void setLastUpdateDatetime(Datetime lastUpdateDatetime) throws StepException {
        if (lastUpdateDatetime == null) {
            String errMsg = "LastUpdateDatetime is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_lastUpdateDatetime = lastUpdateDatetime;
    }


    protected RoleDeprovisioningStep getProvisioningStepById(String stepId) {
        RoleDeprovisioning roleDeprovisioning = getRoleDeprovisioning();
        @SuppressWarnings("unchecked")
        List<RoleDeprovisioningStep> steps = roleDeprovisioning.getRoleDeprovisioningStep();
        for (RoleDeprovisioningStep step : steps) {
            if (step.getStepId().equals(stepId)) {
                return step;
            }
        }
        return null;
    }

    protected RoleDeprovisioningStep getProvisioningStepByType(String stepType) {
        RoleDeprovisioning roleDeprovisioning = getRoleDeprovisioning();
        @SuppressWarnings("unchecked")
        List<RoleDeprovisioningStep> steps = roleDeprovisioning.getRoleDeprovisioningStep();
        for (RoleDeprovisioningStep step : steps) {
            if (step.getType().equalsIgnoreCase(stepType)) {
                return step;
            }
        }
        return null;
    }

    protected RoleDeprovisioningStep getFailedProvisioningStep() {
        RoleDeprovisioning roleDeprovisioning = getRoleDeprovisioning();
        @SuppressWarnings("unchecked")
        List<RoleDeprovisioningStep> steps = roleDeprovisioning.getRoleDeprovisioningStep();
        for (RoleDeprovisioningStep step : steps) {
            if (step.getStepResult().equals(Step.STEP_RESULT_FAILURE)) {
                return step;
            }
        }
        return null;
    }

    protected void setResultProperties(List<Property> resultProps) { m_resultProperties = resultProps; }
    public void addResultProperty(String key, String value) throws StepException {
        String LOGTAG = getStepTag() + "[AbstractStep.addResultProperty] ";
        logger.debug(LOGTAG + "Adding result property " + key + ": " + value);

        if (getResultProperties() == null) {
            setResultProperties(new ArrayList<>());
        }

        Property newProp = roleDeprovisioning.newRoleDeprovisioningStep().newProperty();
        try {
            newProp.setKey(key);
            newProp.setValue(value);
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the field values of a property object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
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
                    logger.debug(LOGTAG + "Found an existing property with key " + key + ". Replaced its value with: " + value);
                }
                catch (EnterpriseFieldException e) {
                    String errMsg = "An error occurred setting the field values of a property object. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, e);
                }
                replacedValue = true;
            }
        }
        // Otherwise, add the new property.
        if (!replacedValue) {
            properties.add(newProp);
            logger.debug(LOGTAG + "No existing property with key " + key + ". Added property with value: " + value);
        }
    }

    public List<Property> getResultProperties() {
        return m_resultProperties;
    }

    protected String getResultProperty(String key) {
        List<Property> resultProperties = getResultProperties();
        for (Property prop : resultProperties) {
            if (prop.getKey().equalsIgnoreCase(key)) {
                return prop.getValue();
            }
        }
        return null;
    }

    protected String getResultProperty(RoleDeprovisioningStep step, String key) {
        @SuppressWarnings("unchecked")
        List<Property> resultProperties = step.getProperty();
        for (Property prop : resultProperties) {
            if (prop.getKey().equalsIgnoreCase(key)) {
                return prop.getValue();
            }
        }
        return null;
    }

    protected void setExecutionStartTime() throws StepException {
        m_executionStartTime = System.currentTimeMillis();

        DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
        String formattedDate = format.format(new java.util.Date(m_executionStartTime));

        addResultProperty("startTime", Long.toString(getExecutionStartTime()));
        addResultProperty("startTimeFormatted", formattedDate);

        logger.info(LOGTAG + "Set step startTime to " + getExecutionStartTime() + " or " + formattedDate);
    }

    protected long getExecutionStartTime() {
        return m_executionStartTime;
    }

    protected void setExecutionTime() throws StepException {
        long currentTime = System.currentTimeMillis();
        m_executionTime = currentTime - getExecutionStartTime();

        addResultProperty("executionTime", Long.toString(getExecutionTime()));

        logger.info(LOGTAG + "Set step executionTime to " + m_executionTime + " = " + currentTime + " - " + m_executionStartTime);
    }

    protected long getExecutionTime() {
        return m_executionTime;
    }

    protected void setEndTime() throws StepException {
        m_executionEndTime = System.currentTimeMillis();

        DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
        String formattedDate = format.format(new java.util.Date(m_executionEndTime));

        addResultProperty("executionEndTime", Long.toString(m_executionEndTime));
        addResultProperty("executionEndTimeFormatted", formattedDate);

        logger.info(LOGTAG + "Set step executionEndTime to " + getExecutionEndTime() + " or " + formattedDate);
    }

    protected long getExecutionEndTime() {
        return m_executionEndTime;
    }

    public void update(String status, String result) throws StepException {
        String LOGTAG = getStepTag() + "[AbstractStep.update] ";
        logger.info(LOGTAG + "Updating step with status " + status + " and result " + result);

        // Update the baseline state of the RoleDeprovisioning
        queryForRoleDeprovisioningBaseline();

        // If the current status is in progress, update the
        // execution time. Note that we don't want to
        // update the execution on update for steps that
        // have already completed or are in any other status
        // that in progress.
        if (getStatus().equalsIgnoreCase(Step.STEP_STATUS_IN_PROGRESS)) {
            // setExecutionTime();
        }

        // If the status is changing from in progress to anything else, set the executionEndTime.
        if (getStatus().equals(Step.STEP_STATUS_IN_PROGRESS) && !status.equals(Step.STEP_STATUS_IN_PROGRESS)) {
            setExecutionTime();
            setEndTime();
        }

        // Update the fields of this step.
        setStatus(status);
        setResult(result);

        // Get the corresponding provisioning step.
        RoleDeprovisioningStep dStep = getProvisioningStepById(getStepId());

        // Update the step values.
        try {
            dStep.setStatus(getStatus());
            dStep.setStepResult(getResult());
            dStep.setProperty(getResultProperties());
            dStep.setLastUpdateUser(AWS_ACCOUNT_SERVICE_USER);
            dStep.setLastUpdateDatetime(new Datetime("LastUpdate", System.currentTimeMillis()));
            dStep.setActualTime(Long.toString(getExecutionTime()));
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the field values of the ProvisioningStep. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Perform the step update.
        try {
            long updateStartTime = System.currentTimeMillis();
            logger.info(LOGTAG + "Updating the RoleDeprovisioning with new step information...");
            getRoleDeprovisioningProvider().update(getRoleDeprovisioning());
            long time = System.currentTimeMillis() - updateStartTime;
            logger.info(LOGTAG = "Updated RoleDeprovisioning with new step state in " + time + " ms.");
        }
        catch (ProviderException pe) {
            String errMsg = "An error occurred updating the RoleDeprovisioning object with an updated ProvisioningStep. The exception is: " + pe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, pe);
        }
    }

    protected void rollback() throws StepException {
        String LOGTAG = "[AbstractStep.rollback] ";
        logger.info(LOGTAG + "Initializing common rollback logic...");
        logger.info(LOGTAG + "Querying for RoleDeprovisioning baseline...");
        queryForRoleDeprovisioningBaseline();
    }

    private void queryForRoleDeprovisioningBaseline() throws StepException {
        // Query for the RoleDeprovisioning object in the AWS Account Service.
        // Get a configured query spec from AppConfig
        RoleDeprovisioningQuerySpecification querySpec;
        try {
            querySpec = (RoleDeprovisioningQuerySpecification) getAppConfig().getObjectByType(RoleDeprovisioningQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Set the values of the query spec.
        try {
            querySpec.setRoleDeprovisioningId(getProvisioningId());
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the RoleDeprovisioning query spec. The exception is: " + efe.getMessage();
              logger.error(LOGTAG + errMsg);
              throw new StepException(errMsg, efe);
        }

        // Log the state of the query spec.
        try {
            logger.info(LOGTAG + "Query spec is: " + querySpec.toXmlString());
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the query spec to XML. The exception is: " + e.getMessage();
              logger.error(LOGTAG + errMsg);
              throw new StepException(errMsg, e);
        }

        List<RoleDeprovisioning> results;
        try {
            results = getRoleDeprovisioningProvider().query(querySpec);
            setRoleDeprovisioning(results.get(0));
        }
        catch (ProviderException pe) {
            String errMsg = "An error occurred querying for the  current state of a RoleDeprovisioning object. The exception is: " + pe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, pe);
        }
    }

    protected String getStepPropertyValue(String stepType, String key) throws StepException {
        String LOGTAG = getStepTag() + "[AbstractStep.getStepPropertyValue] ";

        // Get the property value with the named step and key.
        RoleDeprovisioningStep step = getProvisioningStepByType(stepType);
        String value;
        if (step != null) {
            value = getResultProperty(step, key);
            if (value == null || value.equals(""))
                value = "not available";
            addResultProperty(key, value);
            logger.info(LOGTAG + "Property " + key + " from preceding step " + stepType  + " is " + value);
        }
        else {
            String errMsg = "Step " + stepType + " not found. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        return value;
    }

    protected String getMandatoryStringProperty(String LOGTAG, String propertyName, boolean isSecret) throws StepException {
        String v = getProperties().getProperty(propertyName, null);
        if (v == null || v.equals("")) {
            throw new StepException("No " + propertyName + " property specified. Can't continue.");
        }
        logger.info(LOGTAG + propertyName + " is: " + (isSecret ? "present" : v));
        return v;
    }

    protected int getMandatoryIntegerProperty(String LOGTAG, String propertyName, boolean isSecret) throws StepException {
        String v = getMandatoryStringProperty(LOGTAG, propertyName, isSecret);
        return Integer.parseInt(v);
    }

    protected boolean getMandatoryBooleanProperty(String LOGTAG, String propertyName, boolean isSecret) throws StepException {
        String v = getMandatoryStringProperty(LOGTAG, propertyName, isSecret);
        return Boolean.parseBoolean(v);
    }

    protected String getWithDefaultStringProperty(String LOGTAG, String propertyName, String defaultValue, boolean isSecret) {
        String v = getProperties().getProperty(propertyName, defaultValue);
        logger.info(LOGTAG + propertyName + " is: " + (isSecret ? "present" : v));
        return v;
    }

    protected int getWithDefaultIntegerProperty(String LOGTAG, String propertyName, String defaultValue, boolean isSecret) {
        String v = getWithDefaultStringProperty(LOGTAG, propertyName, defaultValue, isSecret);
        return Integer.parseInt(v);
    }
}
