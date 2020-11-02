/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.RoleProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import org.openeai.config.AppConfig;

import java.util.List;

/**
 * Interface for all RoleProvisioning object providers.
 */
public interface RoleProvisioningProvider {
    String ROLE_PROVISIONING_STATUS_COMPLETED = "completed";
    String ROLE_PROVISIONING_STATUS_PENDING = "pending";
    String ROLE_PROVISIONING_STATUS_ROLLBACK = "rolled back";

    String ROLE_PROVISIONING_RESULT_SUCCESS = "success";
    String ROLE_PROVISIONING_RESULT_FAILURE = "failure";

    /**
     * Initialize provider.
     *
     * @param aConfig an AppConfig object with all this provider needs.
     * @throws ProviderException with details of the initialization error.
     */
    void init(AppConfig aConfig) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/xml/Query-Request.xml
     *
     * @param querySpec the query parameter.
     * @return list of matching RoleProvisioning objects.
     * @throws ProviderException with details of the error.
     */
    List<RoleProvisioning> query(RoleProvisioningQuerySpecification querySpec) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/xml/Generate-Request.xml.
     *
     * @param requisition the generate parameter.
     * @return the generated RoleProvisioning object for the requisition.
     * @throws ProviderException with details of the error.
     */
    RoleProvisioning generate(RoleProvisioningRequisition requisition) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/xml/Create-Request.xml
     *
     * @param rd the new RoleProvisioning object to create.
     * @throws ProviderException with details of the error.
     */
    void create(RoleProvisioning rd) throws ProviderException;
    
    /**
     * message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/xml/Update-Request.xml
     *
     * @param rd the new state of the RoleProvisioning to update.
     * @throws ProviderException with details of the error.
     */
    void update(RoleProvisioning rd) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/xml/Delete-Request.xml
     *
     * @param rd the object to delete.
     * @throws ProviderException with details of the error.
     */
    void delete(RoleProvisioning rd) throws ProviderException;
}