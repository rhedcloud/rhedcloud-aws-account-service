/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.RoleDeprovisioning;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import org.openeai.config.AppConfig;

import java.util.List;

/**
 * Emory RoleDeprovisioning provider.
 */
public class EmoryRoleDeprovisioningProvider implements RoleDeprovisioningProvider {
    @Override
    public void init(AppConfig aConfig) throws ProviderException {

    }

    @Override
    public List<RoleDeprovisioning> query(RoleDeprovisioningQuerySpecification querySpec) throws ProviderException {
        return null;
    }

    @Override
    public RoleDeprovisioning generate(RoleDeprovisioningRequisition requisition) throws ProviderException {
        return null;
    }

    @Override
    public void create(RoleDeprovisioning rd) throws ProviderException {

    }

    @Override
    public void update(RoleDeprovisioning rd) throws ProviderException {

    }

    @Override
    public void delete(RoleDeprovisioning rd) throws ProviderException {

    }
}