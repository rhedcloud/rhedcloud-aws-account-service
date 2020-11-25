/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
import org.openeai.config.AppConfig;

import java.util.List;
import java.util.Properties;

/**
 * Interface for all Provisioning steps.
 */
public interface Step {
    String STEP_RESULT_SUCCESS = "success";
    String STEP_RESULT_FAILURE = "failure";
    String STEP_RESULT_NONE = null;

    String STEP_STATUS_IN_PROGRESS = "in progress";
    String STEP_STATUS_COMPLETED = "completed";
    String STEP_STATUS_PENDING = "pending";
    String STEP_STATUS_ROLLBACK = "rolled back";

    String STEP_EXECUTION_METHOD_PROPERTY_KEY = "stepExecutionMethod";
    String STEP_EXECUTION_METHOD_EXECUTED = "executed";
    String STEP_EXECUTION_METHOD_SIMULATED = "simulated";
    String STEP_EXECUTION_METHOD_SKIPPED = "skipped";
    String STEP_EXECUTION_METHOD_FAILURE = "failure";

    /**
     * Initialize step.
     *
     * @param provisioningId provisioning id
     * @param props step properties
     * @param aConfig AppConfig
     * @param provider provider
     * @throws StepException on error
     */
    void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider provider) throws StepException;

    List<Property> execute() throws StepException;
    void rollback() throws StepException;
    String getStepId();
    String getType();
    String getDescription();
    String getResult();
    List<Property> getResultProperties();
    void update(String status, String result) throws StepException;
    void addResultProperty(String key, String value) throws StepException;
}