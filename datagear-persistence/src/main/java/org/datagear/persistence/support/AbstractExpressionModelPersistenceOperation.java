/*
 * Copyright (c) 2018 datagear.org. All Rights Reserved.
 */

package org.datagear.persistence.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.datagear.connection.JdbcUtil;
import org.datagear.model.Model;
import org.datagear.model.Property;
import org.datagear.model.support.MU;
import org.datagear.persistence.UnsupportedModelCharacterException;
import org.datagear.persistence.support.ExpressionResolver.Expression;
import org.springframework.core.convert.ConversionService;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * 抽象支持表达式的持久化操作类。
 * 
 * @author datagear@163.com
 *
 */
public abstract class AbstractExpressionModelPersistenceOperation extends AbstractModelPersistenceOperation
{
	private ConversionService conversionService;

	private ExpressionResolver variableExpressionResolver;

	private ExpressionResolver sqlExpressionResolver;

	private SpelExpressionParser spelExpressionParser = new SpelExpressionParser();

	public AbstractExpressionModelPersistenceOperation()
	{
		super();
	}

	public AbstractExpressionModelPersistenceOperation(ConversionService conversionService,
			ExpressionResolver variableExpressionResolver, ExpressionResolver sqlExpressionResolver)
	{
		super();
		this.conversionService = conversionService;
		this.variableExpressionResolver = variableExpressionResolver;
		this.sqlExpressionResolver = sqlExpressionResolver;
	}

	public ConversionService getConversionService()
	{
		return conversionService;
	}

	public void setConversionService(ConversionService conversionService)
	{
		this.conversionService = conversionService;
	}

	public ExpressionResolver getVariableExpressionResolver()
	{
		return variableExpressionResolver;
	}

	public void setVariableExpressionResolver(ExpressionResolver variableExpressionResolver)
	{
		this.variableExpressionResolver = variableExpressionResolver;
	}

	public ExpressionResolver getSqlExpressionResolver()
	{
		return sqlExpressionResolver;
	}

	public void setSqlExpressionResolver(ExpressionResolver sqlExpressionResolver)
	{
		this.sqlExpressionResolver = sqlExpressionResolver;
	}

	public SpelExpressionParser getSpelExpressionParser()
	{
		return spelExpressionParser;
	}

	public void setSpelExpressionParser(SpelExpressionParser spelExpressionParser)
	{
		this.spelExpressionParser = spelExpressionParser;
	}

	/**
	 * 解析表达式属性值。
	 * <p>
	 * 如果包含表达式，返回计算结果；否则，直接返回{@code propValue}。
	 * </p>
	 * 
	 * @param cn
	 * @param model
	 * @param property
	 * @param expressionPropValue
	 * @param expressionEvaluationContext
	 * @return
	 */
	protected Object evaluatePropertyValueIfExpression(Connection cn, Model model, Property property, Object propValue,
			ExpressionEvaluationContext expressionEvaluationContext)
	{
		List<Expression> variableExpressions = this.variableExpressionResolver.resolve(propValue);

		Object evaluatedPropValue = propValue;

		if (variableExpressions != null && !variableExpressions.isEmpty())
		{
			String strPropValue = (String) propValue;

			checkValidExpressionProperty(model, property, strPropValue);

			evaluatedPropValue = evaluateVariableExpressions(strPropValue, variableExpressions,
					expressionEvaluationContext);
		}

		List<Expression> sqlExpressions = this.sqlExpressionResolver.resolve(evaluatedPropValue);

		if (sqlExpressions != null && !sqlExpressions.isEmpty())
		{
			String strPropValue = (String) evaluatedPropValue;

			checkValidExpressionProperty(model, property, strPropValue);

			evaluatedPropValue = evaluateSqlExpressions(cn, strPropValue, sqlExpressions, expressionEvaluationContext);
		}

		if (evaluatedPropValue != propValue)
		{
			Class<?> propertyType = property.getModel().getType();
			propValue = this.conversionService.convert(evaluatedPropValue, propertyType);
		}

		return propValue;
	}

	/**
	 * 计算给定变量表达式的值。
	 * 
	 * @param source
	 *            变量表达式字符串
	 * @param expressions
	 * @param expressionEvaluationContext
	 * @return
	 * @throws VariableExpressionErrorException
	 */
	protected Object evaluateVariableExpressions(String source, List<Expression> expressions,
			ExpressionEvaluationContext expressionEvaluationContext) throws VariableExpressionErrorException
	{
		List<Object> expressionValues = new ArrayList<Object>();

		for (int i = 0, len = expressions.size(); i < len; i++)
		{
			Expression expression = expressions.get(i);

			if (expressionEvaluationContext.containsCachedValue(expression))
			{
				Object value = expressionEvaluationContext.getCachedValue(expression);
				expressionValues.add(value);
			}
			else
			{
				evaluateAsVariableExpression(expression, expressionEvaluationContext, expressionValues);
			}
		}

		String evaluated = this.variableExpressionResolver.evaluate(source, expressions, expressionValues, "");

		return evaluated;
	}

	/**
	 * 计算给定SQL表达式的值。
	 * <p>
	 * 如果表达式并不符合{@linkplain #isSelectSql(String)}，那么此方法则会将它作为变量表达式求值，这使得在不需要变量表达式内嵌SQL表达式的情况下，可以仅使用SQL表达式的语法，简化使用难度。
	 * </p>
	 * 
	 * @param cn
	 * @param source
	 *            SQL表达式字符串
	 * @param expressions
	 * @param expressionEvaluationContext
	 * @return
	 * @throws SqlExpressionErrorException
	 * @throws VariableExpressionErrorException
	 */
	protected Object evaluateSqlExpressions(Connection cn, String source, List<Expression> expressions,
			ExpressionEvaluationContext expressionEvaluationContext)
			throws SqlExpressionErrorException, VariableExpressionErrorException
	{
		List<Object> expressionValues = new ArrayList<Object>();

		for (int i = 0, len = expressions.size(); i < len; i++)
		{
			Expression expression = expressions.get(i);

			if (expressionEvaluationContext.containsCachedValue(expression))
			{
				Object value = expressionEvaluationContext.getCachedValue(expression);
				expressionValues.add(value);
			}
			else if (isSelectSql(expression.getContent()))
			{
				evaluateAsSelectSqlExpression(expression, expressionEvaluationContext, expressionValues, cn);
			}
			else
			{
				evaluateAsVariableExpression(expression, expressionEvaluationContext, expressionValues);
			}
		}

		String evaluated = this.sqlExpressionResolver.evaluate(source, expressions, expressionValues, "");

		return evaluated;
	}

	/**
	 * 作为SQL表达式求值。
	 * 
	 * @param expression
	 * @param expressionEvaluationContext
	 * @param expressionValues
	 * @param cn
	 * @throws SqlExpressionErrorException
	 */
	protected void evaluateAsSelectSqlExpression(Expression expression,
			ExpressionEvaluationContext expressionEvaluationContext, List<Object> expressionValues, Connection cn)
			throws SqlExpressionErrorException
	{
		Statement st = null;
		ResultSet rs = null;
		try
		{
			st = cn.createStatement();
			rs = st.executeQuery(expression.getContent());

			Object value = null;

			if (rs.next())
				value = rs.getObject(1);

			expressionValues.add(value);
			expressionEvaluationContext.putCachedValue(expression, value);
		}
		catch (SQLException e)
		{
			throw new SqlExpressionErrorException(expression, e);
		}
		finally
		{
			JdbcUtil.closeResultSet(rs);
			JdbcUtil.closeStatement(st);
		}
	}

	/**
	 * 作为变量表达式求值。
	 * 
	 * @param expression
	 * @param expressionEvaluationContext
	 * @param expressionValues
	 * @throws VariableExpressionErrorException
	 */
	protected void evaluateAsVariableExpression(Expression expression,
			ExpressionEvaluationContext expressionEvaluationContext, List<Object> expressionValues)
			throws VariableExpressionErrorException
	{
		try
		{
			Object value = this.spelExpressionParser.parseExpression(expression.getContent())
					.getValue(expressionEvaluationContext.getVariableExpressionBean());

			expressionValues.add(value);
			expressionEvaluationContext.putCachedValue(expression, value);
		}
		catch (Exception e)
		{
			throw new VariableExpressionErrorException(expression, e);
		}
	}

	/**
	 * 检查属性是否可使用表达式。
	 * 
	 * @param model
	 * @param property
	 * @param expressionPropValue
	 */
	protected void checkValidExpressionProperty(Model model, Property property, String expressionPropValue)
	{
		if (!isValidExpressionProperty(model, property))
			throw new UnsupportedModelCharacterException("[" + model + "] 's [" + property + "] is expression ["
					+ expressionPropValue + "], it must be single, concrete and primitive.");
	}

	/**
	 * 判断属性是否可使用表达式值。
	 * 
	 * @param model
	 * @param property
	 * @return
	 */
	protected boolean isValidExpressionProperty(Model model, Property property)
	{
		return (MU.isSingleProperty(property) && MU.isConcretePrimitiveProperty(property));
	}

	/**
	 * 判断给定对象是否是表达式。
	 * 
	 * @param obj
	 * @return
	 */
	protected boolean isExpression(Object obj)
	{
		return this.variableExpressionResolver.isExpression(obj) || this.sqlExpressionResolver.isExpression(obj);
	}

	/**
	 * 判断给定SQL语句是否是“SELECT”语句。
	 * 
	 * @param sql
	 * @return
	 */
	protected boolean isSelectSql(String sql)
	{
		if (sql == null || sql.isEmpty())
			return false;

		return Pattern.matches(SELECT_SQL_REGEX, sql);
	}

	protected static final String SELECT_SQL_REGEX = "^\\s*((?i)select)\\s+\\S+[\\s\\S]*$";
}
