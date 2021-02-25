/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import org.openeai.config.AppConfig;

import java.util.List;
import java.util.Properties;

/**
 * Interface for all provisioning steps.
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 11 May 2020
 */
public interface Step {
    void init(String provisioningId, Properties props, AppConfig aConfig, AccountDeprovisioningProvider vpcpp) throws StepException;

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