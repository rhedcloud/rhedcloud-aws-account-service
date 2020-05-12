/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.deprovisioning.step;

/**
  * An exception for provisioning step errors.
  * <P>
  * @author      Steve Wheat (swheat@emory.edu)
  * @version     1.0  - 11 May 2020
  */
public class StepException extends Exception {
  /**
   * Default constructor.
   */
  public StepException() {
    super();
  }  
  /**
    * Message constructor.
    */
  public StepException(String msg) {
    super(msg);
  }
  /**
    * Throwable constructor.
    */
  public StepException(String msg, Throwable e) {
    super(msg, e);
  }
}