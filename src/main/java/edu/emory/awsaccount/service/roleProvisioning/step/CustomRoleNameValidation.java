/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import org.openeai.config.AppConfig;

import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;


/**
 * Validate the name chosen for the custom role.
 */
public class CustomRoleNameValidation extends AbstractStep implements Step {
    private static final Pattern ALPHANUMERIC = Pattern.compile("^[A-Za-z0-9]+$");

    public void init (String provisioningId, Properties props, AppConfig aConfig, RoleProvisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);
        
        String LOGTAG = getStepTag() + "[CustomRoleNameValidation.init] ";
        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CustomRoleNameValidation.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleProvisioningRequisition roleProvisioningRequisition = getRoleProvisioning().getRoleProvisioningRequisition();
        String accountId = roleProvisioningRequisition.getAccountId();
        String roleName = roleProvisioningRequisition.getRoleName();

        String stepResult;
        String validationMessage;

        if (roleName == null) {
            stepResult = STEP_RESULT_FAILURE;
            validationMessage = "Custom role name must not be NULL.";
        }
        else if (roleName.length() == 0) {
            stepResult = STEP_RESULT_FAILURE;
            validationMessage = "Custom role name must not be empty.";
        }
        else if (roleName.length() > 43) {
            stepResult = STEP_RESULT_FAILURE;
            validationMessage = "Custom role name exceeds maximum length.";
        }
        else if (!ALPHANUMERIC.matcher(roleName).matches()) {
            stepResult = STEP_RESULT_FAILURE;
            validationMessage = "Custom role name may only contain alphanumerics.";
        }
        else {
            stepResult = STEP_RESULT_SUCCESS;
            validationMessage = "Custom role name is valid.";
        }

        // set result properties
        addResultProperty("accountId", accountId);
        addResultProperty("customRoleName", roleName);
        addResultProperty("validationMessage", validationMessage);

        // Update the step.
        update(STEP_STATUS_COMPLETED, stepResult);
        
        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");
        
        // Return the properties.
        return getResultProperties();
    }
    
    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CustomRoleNameValidation.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);

        // simulated result properties
        addResultProperty("accountId", "123456789012");
        addResultProperty("customRoleName", "SimulatedRoleName");
        addResultProperty("validationMessage", "Custom role name validation was simulated.");

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
        String LOGTAG = getStepTag() + "[CustomRoleNameValidation.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_FAILURE);

        // simulated result properties
        addResultProperty("accountId", "123456789012");
        addResultProperty("customRoleName", "fail");
        addResultProperty("validationMessage", "Custom role name validation was forced to fail.");

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
        String LOGTAG = getStepTag() + "[CustomRoleNameValidation.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);
        
        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }
}
