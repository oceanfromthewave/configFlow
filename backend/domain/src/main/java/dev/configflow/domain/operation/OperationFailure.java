package dev.configflow.domain.operation;

import java.util.Map;
import java.util.Objects;

public record OperationFailure(String code, String message, Map<String, String> context)
{
	public OperationFailure
	{
		Objects.requireNonNull(code, "code must not be null");

		context = Map.copyOf(context == null ? Map.of() : context);
	}

	public static OperationFailure of(String code, String message)
	{
		return new OperationFailure(code,message,Map.of());
	}

}
