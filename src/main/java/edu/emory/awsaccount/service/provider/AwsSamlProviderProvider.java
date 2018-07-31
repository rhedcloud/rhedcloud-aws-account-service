/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.ArrayList;
import java.util.List;

// Log4j
import org.apache.log4j.Category;
import org.apache.log4j.Logger;
// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
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
import com.amazonaws.services.identitymanagement.model.SAMLProviderListEntry;

/**
 *
 */
public class AwsSamlProviderProvider extends AbstractAwsCrudProvider<SamlProvider, SamlProviderQuerySpecification> {

    private static Logger logger = Logger.getLogger(AwsSamlProviderProvider.class);
    private String LOGTAG = "[AwsSamlProviderProvider] ";

    @Override
    public List<SamlProvider> query(SamlProviderQuerySpecification querySpec)
            throws org.openeai.jms.consumer.commands.provider.ProviderException {
        if (querySpec.getAccountId() == null || querySpec.getAccountId().equals("")) {
            String errMsg = "The accountId is null. Cannot contiue.";
            throw new org.openeai.jms.consumer.commands.provider.ProviderException(errMsg);
        }
        List<SamlProvider> samlProviders = new ArrayList<>();
        ListSAMLProvidersRequest request = new ListSAMLProvidersRequest();

        ListSAMLProvidersResult result = buildIamClient(querySpec.getAccountId()).listSAMLProviders(request);
        // Replace the object in the map with the same StackId.
        // TODO: check result
        for (SAMLProviderListEntry entry : result.getSAMLProviderList()) {
            try {
                SamlProvider samlProvider = (SamlProvider) appConfig.getObjectByType(SamlProvider.class.getName());
                String arn = entry.getArn();
                logger.info(LOGTAG + "arn=" + arn);
                // entry.getCreateDate();
                // entry.getValidUntil();
                samlProvider.setName(parseNameFromArn(arn));
                samlProvider.setAccountId(parseAccountIdFromArn(arn));
                samlProviders.add(samlProvider);
            } catch (EnterpriseConfigurationObjectException | EnterpriseFieldException e) {
                logger.error(LOGTAG, e);
                throw new org.openeai.jms.consumer.commands.provider.ProviderException(e.getMessage());
            }
        }
        return samlProviders;
    }

    // arn:aws:iam::123456789012:saml-provider/ADFSProvider
    private static String parseAccountIdFromArn(String arn) {
        String[] parts = arn.split(":");
        return parts[4];
    }
    private static String parseNameFromArn(String arn) {
        return arn.substring(arn.indexOf("/") + 1);
    }

    @Override
    public void create(SamlProvider req) throws org.openeai.jms.consumer.commands.provider.ProviderException {
        CreateSAMLProviderRequest request = new CreateSAMLProviderRequest();
        request.setName(req.getName());
        request.setSAMLMetadataDocument(req.getSamlMetadataDocument());
        CreateSAMLProviderResult result = buildIamClient(req.getAccountId()).createSAMLProvider(request);
        logger.info(LOGTAG + "arn=" + result.getSAMLProviderArn());
    }
    @Override
    public void delete(SamlProvider samlProvider) throws org.openeai.jms.consumer.commands.provider.ProviderException {
        DeleteSAMLProviderRequest request = new DeleteSAMLProviderRequest();
        request.setSAMLProviderArn("arn:aws:iam::" + samlProvider.getAccountId() + ":saml-provider/" + samlProvider.getName());
        DeleteSAMLProviderResult result = buildIamClient(samlProvider.getAccountId()).deleteSAMLProvider(request);
        return;
    }

}
