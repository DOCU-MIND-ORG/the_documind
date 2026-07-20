package com.accenture.intern.docmind;

import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.filter.converter.PineconeFilterExpressionConverter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;

public class FilterTest {
    public static void main(String[] args) {
        try {
            FilterExpressionTextParser parser = new FilterExpressionTextParser();
            PineconeFilterExpressionConverter conv = new PineconeFilterExpressionConverter();
            
            String filterStr = "sourceName in ['loki (tv series)', 'spider man: no way home']";
            System.out.println("Input: " + filterStr);
            Expression exp = parser.parse(filterStr);
            System.out.println("Parsed expression: " + exp);
            System.out.println("Converted Pinecone JSON: " + conv.convertExpression(exp));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
