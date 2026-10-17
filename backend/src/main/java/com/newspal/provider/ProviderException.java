package com.newspal.provider;

/**
 * A news provider call failed. Messages must never contain credentials.
 */
public class ProviderException extends RuntimeException {

	public ProviderException(String message) {
		super(message);
	}

}
