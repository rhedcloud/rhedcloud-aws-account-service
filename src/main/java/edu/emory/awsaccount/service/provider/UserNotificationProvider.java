/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.util.List;

// OpenEAI config foundation
import org.openeai.config.AppConfig;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;


/**
 * Interface for all UserNotification object providers.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 5 July 2018
 */
public interface UserNotificationProvider {
    /**
     * 
     * <P>
     * 
     * @param AppConfig
     *            , an AppConfig object with all this provider needs.
     *            <P>
     * @throws ProviderException
     *             with details of the initialization error.
     */
    public void init(AppConfig aConfig) throws ProviderException;

    /**
     * 
     * <P>
     * 
     * @param String, the UserId.
     * @param AccountNotification, the account notification with which
     *        to create the UserNotification
     * @return Stack, a generated UserNotification.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public UserNotification generate(String userId, AccountNotification aNotification) 
    	throws ProviderException;
    
    /**
     * 
     * <P>
     * 
     * @param String, the AccountId.
     * @return List, a list of UserIds associated with the account.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public List<String> getUserIdsForAccount(String accountId) 
    	throws ProviderException;
    
    /**
     * 
     * <P>
     * 
     * @param UserNotification, the UserNotification.
     *         <P>
     * @throws ProviderException with details of the error generating the stack.
     */
    public void processAdditionalNotifications(UserNotification notification) 
    	throws ProviderException; 
    
}