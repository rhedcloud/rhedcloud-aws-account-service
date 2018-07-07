/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.List;

// Log4j
import org.apache.log4j.Category;
import org.apache.log4j.Logger;
// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.jms.consumer.commands.provider.AbstractCrudProvider;

//AWS Message Object API (MOA)

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.SamlProvider;
import com.amazon.aws.moa.objects.resources.v1_0.SamlProviderQuerySpecification;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.CreateSAMLProviderRequest;
import com.amazonaws.services.identitymanagement.model.CreateSAMLProviderResult;
import com.amazonaws.services.identitymanagement.model.DeleteSAMLProviderRequest;
import com.amazonaws.services.identitymanagement.model.DeleteSAMLProviderResult;
import com.amazonaws.services.identitymanagement.model.ListSAMLProvidersRequest;
import com.amazonaws.services.identitymanagement.model.ListSAMLProvidersResult;

/**
 * An example object provider that maintains an in-memory store of stacks.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class AwsSamlProviderProvider extends AbstractCrudProvider<SamlProvider, SamlProviderQuerySpecification> {

    private static Logger logger = Logger.getLogger(AwsSamlProviderProvider.class);
    private String LOGTAG = "[AwsSamlProviderProvider] ";
    private AmazonIdentityManagement amazonIdentityManagement = AmazonIdentityManagementClientBuilder.defaultClient();

    /**
     * @see StackProvider.java
     * 
     *      Note: this implementation queries by StackId.
     */
    @Override
    public List<SamlProvider> query(SamlProviderQuerySpecification querySpec)
            throws org.openeai.jms.consumer.commands.provider.ProviderException {

        // If the StackId is null, throw an exception.
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "The StackId is null. The ExampleStackProvider" + "presently only implements query by StackId.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }

        ListSAMLProvidersRequest request = new ListSAMLProvidersRequest();
        // TODO: querySpec to request
        ListSAMLProvidersResult result = amazonIdentityManagement.listSAMLProviders(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result

        return null;
    }

    /**
     * @see StackProvider.java
     */
    @Override
    public void create(SamlProvider req) throws org.openeai.jms.consumer.commands.provider.ProviderException {

        // Get a configured Stack object from AppConfig
        SamlProvider samlProvider = new SamlProvider();
        try {
            samlProvider = (SamlProvider) appConfig.getObjectByType(samlProvider.getClass().getName());
            // TODO: map req to request
            CreateSAMLProviderRequest request = new CreateSAMLProviderRequest();
            CreateSAMLProviderResult result = amazonIdentityManagement.createSAMLProvider(request);
            // TODO: check result
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg, ecoe);
        }
    }

    /**
     * @see StackProvider.java
     */
    @Override
    public void delete(SamlProvider stack) throws org.openeai.jms.consumer.commands.provider.ProviderException {

        DeleteSAMLProviderRequest request = new DeleteSAMLProviderRequest();
        // TODO: samlProvier to request
        DeleteSAMLProviderResult result = amazonIdentityManagement.deleteSAMLProvider(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result));

        return;
    }

}
