/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayStatus;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayStatusQuerySpecification;
import org.openeai.config.AppConfig;

import java.util.List;

/**
 * Interface for all TransitGatewayStatus object providers.
 */
public interface TransitGatewayStatusProvider {
    /**
     * Initialize provider.
     *
     * @param aConfig an AppConfig object with all this provider needs.
     * @throws ProviderException with details of the initialization error.
     */
    void init(AppConfig aConfig) throws ProviderException;

    /**
     * message/releases/edu/emory/Network/TransitGatewayStatus/1.0/xml/Query-Request.xml
     *
     * @param querySpec the query parameter.
     * @return list of matching objects.
     * @throws ProviderException with details of the error.
     */
    List<TransitGatewayStatus> query(TransitGatewayStatusQuerySpecification querySpec) throws ProviderException;
}