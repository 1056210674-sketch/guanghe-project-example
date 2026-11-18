package com.guanghe.backend.geo;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoTaskMapperReportProjectionTests {
    private static final Pattern REPORT_SELECT = Pattern.compile(
            "(?is)\\bSELECT\\s+(.*?)\\s+FROM\\s+business\\.geo_report\\b");
    private static final Pattern ALIAS = Pattern.compile("(?i)\\bAS\\s+(\\w+)\\s*$");
    private static final Pattern SIMPLE_COLUMN = Pattern.compile("(?i)\\b\\w+\\.(\\w+)\\s*$");

    @Test
    void reportByRunColumnsMatchReportRecordConstructor() throws NoSuchMethodException {
        assertReportProjection("findReportByRun", long.class);
    }

    @Test
    void reportDetailColumnsMatchReportRecordConstructor() throws NoSuchMethodException {
        assertReportProjection("findReport", long.class, long.class);
    }

    @Test
    void reportPageColumnsMatchReportRecordConstructor() throws NoSuchMethodException {
        assertReportProjection("findReportPage", long.class, Long.class, Long.class,
                Boolean.class, long.class, int.class);
    }

    private static void assertReportProjection(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = GeoTaskMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("@Select on %s", methodName).isNotNull();

        String sql = String.join("\n", select.value());
        Matcher query = REPORT_SELECT.matcher(sql);
        assertThat(query.find()).as("report SELECT in %s", methodName).isTrue();

        List<String> expected = Arrays.stream(GeoTaskRecords.Report.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> actual = splitTopLevelColumns(query.group(1)).stream()
                .map(GeoTaskMapperReportProjectionTests::columnName)
                .toList();
        assertThat(actual).as("%s projection must follow the Report constructor order", methodName)
                .containsExactlyElementsOf(expected);
    }

    private static List<String> splitTopLevelColumns(String projection) {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < projection.length(); i++) {
            char current = projection.charAt(i);
            if (current == '(') depth++;
            else if (current == ')') depth--;
            else if (current == ',' && depth == 0) {
                columns.add(projection.substring(start, i).trim());
                start = i + 1;
            }
        }
        assertThat(depth).as("balanced projection parentheses").isZero();
        columns.add(projection.substring(start).trim());
        return columns;
    }

    private static String columnName(String expression) {
        Matcher alias = ALIAS.matcher(expression);
        if (alias.find()) return alias.group(1);

        Matcher simple = SIMPLE_COLUMN.matcher(expression);
        assertThat(simple.find()).as("column %s has an alias or a simple table.column name", expression)
                .isTrue();
        return simple.group(1);
    }
}
