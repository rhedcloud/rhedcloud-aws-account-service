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
 * Interface for all RoleDeprovisioning object providers.
 */
public interface RoleDeprovisioningProvider {
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
     * message/releases/com/amazon/aws/Provisioning/RoleDeprovisioning/1.0/xml/Query-Request.xml
     *
     * @param querySpec the query parameter.
     * @return list of matching RoleDeprovisioning objects.
     * @throws ProviderException with details of the error.
     */
    List<RoleDeprovisioning> query(RoleDeprovisioningQuerySpecification querySpec) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleDeprovisioning/1.0/xml/Generate-Request.xml.
     *
     * @param requisition the generate parameter.
     * @return the generated RoleDeprovisioning object for the requisition.
     * @throws ProviderException with details of the error.
     */
    RoleDeprovisioning generate(RoleDeprovisioningRequisition requisition) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleDeprovisioning/1.0/xml/Create-Request.xml
     *
     * @param rd the new RoleDeprovisioning object to create.
     * @throws ProviderException with details of the error.
     */
    void create(RoleDeprovisioning rd) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleDeprovisioning/1.0/xml/Update-Request.xml
     *
     * @param rd the new state of the RoleDeprovisioning to update.
     * @throws ProviderException with details of the error.
     */
    void update(RoleDeprovisioning rd) throws ProviderException;

    /**
     * message/releases/com/amazon/aws/Provisioning/RoleDeprovisioning/1.0/xml/Delete-Request.xml
     *
     * @param rd the object to delete.
     * @throws ProviderException with details of the error.
     */
    void delete(RoleDeprovisioning rd) throws ProviderException;
}