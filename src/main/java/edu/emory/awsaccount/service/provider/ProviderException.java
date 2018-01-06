/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

/**
  * An exception for AWS Account Service object provider errors.
  * <P>
  * @author      Steve Wheat (swheat@emory.edu)
  * @version     1.0  - 5 June 2016
  */
public class ProviderException extends Exception {
  /**
   * Default constructor.
   */
  public ProviderException() {
    super();
  }  
  /**
    * Message constructor.
    */
  public ProviderException(String msg) {
    super(msg);
  }
  /**
    * Throwable constructor.
    */
  public ProviderException(String msg, Throwable e) {
    super(msg, e);
  }
}