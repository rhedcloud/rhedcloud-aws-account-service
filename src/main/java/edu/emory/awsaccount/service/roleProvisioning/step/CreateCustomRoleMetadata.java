/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.CustomRole;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;


/**
 * Create the CustomRole metadata.
 */
public class CreateCustomRoleMetadata extends AbstractStep implements Step {
    private ProducerPool awsAccountServiceProducerPool = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleProvisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[CreateCustomRoleMetadata.init] ";

        // This step needs to send messages to the AWS account service to create CustomRole metadata.
        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateCustomRoleMetadata.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleProvisioningRequisition roleProvisioningRequisition = getRoleProvisioning().getRoleProvisioningRequisition();
        String accountId = roleProvisioningRequisition.getAccountId();
        String roleName = roleProvisioningRequisition.getRoleName();

        // Get some properties from previous steps.
        String customRoleIdmName = getStepPropertyValue("CREATE_IDM_ROLE_AND_RESOURCES_FOR_CUSTOM_ROLE", "customRoleIdmName");

        // Get a configured CustomRole from AppConfig.
        CustomRole customRole;
        try {
            customRole = (CustomRole) getAppConfig().getObjectByType(CustomRole.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting CustomRole properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        try {
            customRole.setAccountId(accountId);
            customRole.setRoleName(roleName);
            customRole.setIdmRoleName(customRoleIdmName);
            customRole.setCreateUser("AwsAccountService");
            customRole.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the CustomRole object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Log the state of the CustomRole.
        try {
            logger.info(LOGTAG + "CustomRole to create is: " + customRole.toXmlString());
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the new CustomRole to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long createStartTime = System.currentTimeMillis();
            customRole.create(rs);
            long createTime = System.currentTimeMillis() - createStartTime;
            logger.info(LOGTAG + "Create CustomRole in " + createTime + " ms.");
        }
        catch (EnterpriseObjectCreateException e) {
            String errMsg = "An error occurred creating the new CustomRole. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer)rs);
        }

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateCustomRoleMetadata.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateCustomRoleMetadata.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_FAILURE);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_FAILURE);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateCustomRoleMetadata.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    public ProducerPool getAwsAccountServiceProducerPool() { return awsAccountServiceProducerPool; }
    public void setAwsAccountServiceProducerPool(ProducerPool v) { this.awsAccountServiceProducerPool = v; }
}
