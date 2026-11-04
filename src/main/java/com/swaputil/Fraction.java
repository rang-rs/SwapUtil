package com.swaputil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

final class Fraction
{
	static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
	private final BigInteger numerator;
	private final BigInteger denominator;

	Fraction(long value, int divisor) { this(BigInteger.valueOf(value), BigInteger.valueOf(divisor)); }

	private Fraction(BigInteger numerator, BigInteger denominator)
	{
		if (denominator.signum() <= 0) { throw new IllegalArgumentException("Divisor must be positive"); }
		BigInteger gcd = numerator.gcd(denominator);
		this.numerator = numerator.divide(gcd);
		this.denominator = denominator.divide(gcd);
	}

	Fraction add(Fraction other)
	{
		return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
			denominator.multiply(other.denominator));
	}

	Fraction negate() { return new Fraction(numerator.negate(), denominator); }
	int signum() { return numerator.signum(); }
	BigDecimal rounded() { return new BigDecimal(numerator).divide(new BigDecimal(denominator), 0, RoundingMode.HALF_UP); }
}
