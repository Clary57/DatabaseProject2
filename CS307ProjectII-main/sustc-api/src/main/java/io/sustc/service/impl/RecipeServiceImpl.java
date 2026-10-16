package io.sustc.service.impl;

import io.fury.shaded.org.codehaus.commons.nullanalysis.Nullable;
import io.sustc.dto.*;
import io.sustc.service.RecipeService;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RecipeServiceImpl implements RecipeService {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public String getNameFromID(long id) {
        String sql = "SELECT Name FROM recipes WHERE RecipeId = ?";

        try {
            return jdbcTemplate.queryForObject(sql, String.class, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public RecipeRecord getRecipeById(long recipeId) throws IllegalArgumentException {
        if (recipeId <= 0) {
            throw new IllegalArgumentException("Recipe ID must be greater than 0");
        }

        String sql = """
        SELECT 
            r.RecipeId,
            r.Name,
            r.AuthorId,
            u.AuthorName,
            r.CookTime,
            r.PrepTime,
            r.TotalTime,
            r.DatePublished,
            r.Description,
            r.RecipeCategory,
            r.AggregatedRating,
            r.ReviewCount,
            r.Calories,
            r.FatContent,
            r.SaturatedFatContent,
            r.CholesterolContent,
            r.SodiumContent,
            r.CarbohydrateContent,
            r.FiberContent,
            r.SugarContent,
            r.ProteinContent,
            r.RecipeServings,
            r.RecipeYield
        FROM recipes r
        LEFT JOIN users u ON r.AuthorId = u.AuthorId
        WHERE r.RecipeId = ? AND u.IsDeleted = FALSE
        """;

        try {
            RecipeRecord record = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                RecipeRecord recipe = new RecipeRecord();

                recipe.setRecipeId(rs.getLong("RecipeId"));
                recipe.setName(rs.getString("Name"));
                recipe.setAuthorId(rs.getLong("AuthorId"));
                recipe.setAuthorName(rs.getString("AuthorName"));
                recipe.setCookTime(rs.getString("CookTime"));
                recipe.setPrepTime(rs.getString("PrepTime"));
                recipe.setTotalTime(rs.getString("TotalTime"));

                Timestamp datePublished = rs.getTimestamp("DatePublished");
                recipe.setDatePublished(datePublished);

                recipe.setDescription(rs.getString("Description"));
                recipe.setRecipeCategory(rs.getString("RecipeCategory"));
                recipe.setAggregatedRating(rs.getFloat("AggregatedRating"));
                recipe.setReviewCount(rs.getInt("ReviewCount"));
                recipe.setCalories(rs.getFloat("Calories"));
                recipe.setFatContent(rs.getFloat("FatContent"));
                recipe.setSaturatedFatContent(rs.getFloat("SaturatedFatContent"));
                recipe.setCholesterolContent(rs.getFloat("CholesterolContent"));
                recipe.setSodiumContent(rs.getFloat("SodiumContent"));
                recipe.setCarbohydrateContent(rs.getFloat("CarbohydrateContent"));
                recipe.setFiberContent(rs.getFloat("FiberContent"));
                recipe.setSugarContent(rs.getFloat("SugarContent"));
                recipe.setProteinContent(rs.getFloat("ProteinContent"));
                recipe.setRecipeServings(rs.getInt("RecipeServings"));
                recipe.setRecipeYield(rs.getString("RecipeYield"));

                return recipe;
            }, recipeId);

            if (record != null) {
                String[] ingredients = getAndSortRecipeIngredients(recipeId);
                record.setRecipeIngredientParts(ingredients);
            }

            return record;

        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (DataAccessException e) {
            return null;
        }
    }

    private String[] getAndSortRecipeIngredients(long recipeId) {
        String sql = "SELECT IngredientPart FROM recipe_ingredients WHERE RecipeId = ?";

        try {
            List<String> ingredients = jdbcTemplate.query(sql,
                    (rs, rowNum) -> rs.getString("IngredientPart"), recipeId);

            if (ingredients == null || ingredients.isEmpty()) {
                return null;
            }

            String[] ingredientArray = ingredients.toArray(new String[0]);
            Arrays.sort(ingredientArray, String.CASE_INSENSITIVE_ORDER);

            return ingredientArray;

        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public PageResult<RecipeRecord> searchRecipes(
            String keyword,
            String category,
            Double minRating,
            Integer page,
            Integer size,
            String sort) throws IllegalArgumentException {

        if (page == null || page < 1) {
            throw new IllegalArgumentException("Page number must be at least 1");
        }
        if (size == null || size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than 0");
        }

        List<Object> params = new ArrayList<>();
        StringBuilder whereClause = new StringBuilder(" WHERE u.IsDeleted = FALSE ");

        if (keyword != null && !keyword.trim().isEmpty()) {
            whereClause.append(" AND (LOWER(r.Name) LIKE LOWER(?) OR LOWER(r.Description) LIKE LOWER(?)) ");
            String keywordPattern = "%" + keyword.trim() + "%";
            params.add(keywordPattern);
            params.add(keywordPattern);
        }

        if (category != null && !category.trim().isEmpty()) {
            whereClause.append(" AND r.RecipeCategory = ? ");
            params.add(category.trim());
        }

        if (minRating != null) {
            whereClause.append(" AND r.AggregatedRating >= ? ");
            params.add(minRating);
        }

        String orderByClause = buildSortClause(sort);

        String countSql = "SELECT COUNT(*) FROM recipes r " +
                "LEFT JOIN users u ON r.AuthorId = u.AuthorId " + whereClause;

        long total;
        try {
            total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        } catch (Exception e) {
            total = 0;
        }

        if (total == 0) {
            return new PageResult<>(Collections.emptyList(), page, size, total);
        }

        int offset = (page - 1) * size;
        String querySql = buildQuerySql(whereClause.toString(), orderByClause);

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(size);
        queryParams.add(offset);

        List<RecipeRecord> recipes = executeSearchQuery(querySql, queryParams);

        if (!recipes.isEmpty()) {
            loadAndSortIngredientsInBatch(recipes);
        }

        return new PageResult<>(recipes, page, size, total);
    }

    private String buildSortClause(String sort) {
        if (sort == null || sort.trim().isEmpty()) {
            return " ORDER BY r.RecipeId ASC ";
        }

        switch (sort.trim()) {
            case "rating_desc":
                return " ORDER BY r.AggregatedRating DESC NULLS LAST, r.RecipeId DESC ";
            case "date_desc":
                return " ORDER BY r.DatePublished DESC NULLS LAST, r.RecipeId DESC ";
            case "calories_asc":
                return " ORDER BY r.Calories ASC NULLS LAST, r.RecipeId ASC ";
            default:
                return " ORDER BY r.RecipeId ASC ";
        }
    }

    private String buildQuerySql(String whereClause, String orderByClause) {
        return """
        SELECT 
            r.RecipeId,
            r.Name,
            r.AuthorId,
            u.AuthorName,
            r.CookTime,
            r.PrepTime,
            r.TotalTime,
            r.DatePublished,
            r.Description,
            r.RecipeCategory,
            r.AggregatedRating,
            r.ReviewCount,
            r.Calories,
            r.FatContent,
            r.SaturatedFatContent,
            r.CholesterolContent,
            r.SodiumContent,
            r.CarbohydrateContent,
            r.FiberContent,
            r.SugarContent,
            r.ProteinContent,
            r.RecipeServings,
            r.RecipeYield
        FROM recipes r
        LEFT JOIN users u ON r.AuthorId = u.AuthorId
        """ + whereClause + orderByClause + " LIMIT ? OFFSET ?";
    }

    private List<RecipeRecord> executeSearchQuery(String sql, List<Object> params) {
        try {
            return jdbcTemplate.query(sql, params.toArray(), (rs, rowNum) -> {
                RecipeRecord recipe = new RecipeRecord();

                mapBasicRecipeFields(rs, recipe);

                recipe.setRecipeIngredientParts(null);

                return recipe;
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void loadAndSortIngredientsInBatch(List<RecipeRecord> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return;
        }

        List<Long> recipeIds = recipes.stream()
                .map(RecipeRecord::getRecipeId)
                .collect(Collectors.toList());

        String placeholders = String.join(",", Collections.nCopies(recipeIds.size(), "?"));
        String sql = "SELECT RecipeId, IngredientPart FROM recipe_ingredients " +
                "WHERE RecipeId IN (" + placeholders + ")";

        try {
            Map<Long, List<String>> ingredientsByRecipe = new HashMap<>();

            jdbcTemplate.query(sql, recipeIds.toArray(), (rs, rowNum) -> {
                Long recipeId = rs.getLong("RecipeId");
                String ingredient = rs.getString("IngredientPart");

                ingredientsByRecipe.computeIfAbsent(recipeId, k -> new ArrayList<>())
                        .add(ingredient);

                return null;
            });

            for (RecipeRecord recipe : recipes) {
                List<String> ingredients = ingredientsByRecipe.get(recipe.getRecipeId());
                if (ingredients != null && !ingredients.isEmpty()) {
                    String[] ingredientArray = ingredients.toArray(new String[0]);
                    Arrays.sort(ingredientArray, String.CASE_INSENSITIVE_ORDER);
                    recipe.setRecipeIngredientParts(ingredientArray);
                } else {
                    recipe.setRecipeIngredientParts(new String[0]);
                }
            }

        } catch (Exception e) {
            for (RecipeRecord recipe : recipes) {
                String[] ingredients = getAndSortRecipeIngredients(recipe.getRecipeId());
                recipe.setRecipeIngredientParts(ingredients);
            }
        }
    }

    private void mapBasicRecipeFields(ResultSet rs, RecipeRecord recipe) throws SQLException {
        recipe.setRecipeId(rs.getLong("RecipeId"));
        recipe.setName(rs.getString("Name"));
        recipe.setAuthorId(rs.getLong("AuthorId"));
        recipe.setAuthorName(rs.getString("AuthorName"));
        recipe.setCookTime(rs.getString("CookTime"));
        recipe.setPrepTime(rs.getString("PrepTime"));
        recipe.setTotalTime(rs.getString("TotalTime"));
        recipe.setDatePublished(rs.getTimestamp("DatePublished"));
        recipe.setDescription(rs.getString("Description"));
        recipe.setRecipeCategory(rs.getString("RecipeCategory"));
        recipe.setAggregatedRating(rs.getFloat("AggregatedRating"));
        recipe.setReviewCount(rs.getInt("ReviewCount"));
        recipe.setCalories(rs.getFloat("Calories"));
        recipe.setFatContent(rs.getFloat("FatContent"));
        recipe.setSaturatedFatContent(rs.getFloat("SaturatedFatContent"));
        recipe.setCholesterolContent(rs.getFloat("CholesterolContent"));
        recipe.setSodiumContent(rs.getFloat("SodiumContent"));
        recipe.setCarbohydrateContent(rs.getFloat("CarbohydrateContent"));
        recipe.setFiberContent(rs.getFloat("FiberContent"));
        recipe.setSugarContent(rs.getFloat("SugarContent"));
        recipe.setProteinContent(rs.getFloat("ProteinContent"));
        recipe.setRecipeServings(rs.getInt("RecipeServings"));
        recipe.setRecipeYield(rs.getString("RecipeYield"));
    }

    @Override
    public long createRecipe(RecipeRecord dto, AuthInfo auth) {
        // 1. 基础参数检查
        if (auth == null || auth.getAuthorId() <= 0) return -1;
        if (dto == null || dto.getName() == null || dto.getName().trim().isEmpty()) return -1;
        if (dto.getAuthorId() != auth.getAuthorId()) return -1;

        // 【核心修复步骤】
        // 在 Java 层把 "PT10M" 解析成数字 10
        // 如果直接传字符串给数据库，数据库会报 "invalid input syntax for integer"
        Integer cookTime = parseIsoDuration(dto.getCookTime());
        Integer prepTime = parseIsoDuration(dto.getPrepTime());
        Integer totalTime = parseIsoDuration(dto.getTotalTime());

        // 2. SQL 语句
        // 这里去掉了 ?::integer，因为我们已经在 Java 里转成整数了，JDBC 会自动处理
        String sql = """
            SELECT create_recipe_optimized(
                ?, ?, 
                ?, ?, ?, 
                ?::timestamp, 
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
        """;

        try {
            Timestamp publishDate = dto.getDatePublished() != null ?
                    dto.getDatePublished() : new Timestamp(System.currentTimeMillis());

            // 3. 执行调用
            Long newId = jdbcTemplate.queryForObject(sql, Long.class,
                    dto.getName(),
                    auth.getAuthorId(),
                    cookTime,    // 传解析后的整数 (比如 10)
                    prepTime,    // 传解析后的整数
                    totalTime,   // 传解析后的整数
                    publishDate,
                    dto.getDescription(),
                    dto.getRecipeCategory(),
                    dto.getAggregatedRating(),
                    dto.getReviewCount(),
                    dto.getCalories(),
                    dto.getFatContent(),
                    dto.getSaturatedFatContent(),
                    dto.getCholesterolContent(),
                    dto.getSodiumContent(),
                    dto.getCarbohydrateContent(),
                    dto.getFiberContent(),
                    dto.getSugarContent(),
                    dto.getProteinContent(),
                    dto.getRecipeServings(),
                    dto.getRecipeYield(),
                    dto.getRecipeId()
            );

            return (newId != null) ? newId : -1;

        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    private Integer parseIsoDuration(Object durationObj) {
        if (durationObj == null) return null;
        String str = durationObj.toString();
        if (str.trim().isEmpty()) return null;
        try {
            return (int) Duration.parse(str).toMinutes();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecipe(long recipeId, AuthInfo auth) throws SecurityException {
        validateDeleteRecipeParams(recipeId, auth);

        validateUserAuth(auth);

        validateRecipeOwnership(recipeId, auth.getAuthorId());

        executeCascadeDelete(recipeId);
    }

    private void validateDeleteRecipeParams(long recipeId, AuthInfo auth) {
        if (auth == null) {
            throw new SecurityException("Authentication info is required");
        }

        if (recipeId <= 0) {
            throw new IllegalArgumentException("Recipe ID must be positive");
        }
    }

    private void validateUserAuth(AuthInfo auth) throws SecurityException {
        String sql = "SELECT 1 FROM users WHERE AuthorId = ? AND IsDeleted = FALSE";

        try {
            Integer result = jdbcTemplate.queryForObject(sql, Integer.class, auth.getAuthorId());
            if (result == null || result != 1) {
                throw new SecurityException("User is invalid or inactive");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User not found or inactive");
        } catch (Exception e) {
            throw new SecurityException("Failed to validate user identity");
        }
    }

    private void validateRecipeOwnership(long recipeId, long userId) throws SecurityException {
        String sql = "SELECT AuthorId FROM recipes WHERE RecipeId = ?";

        try {
            Long authorId = jdbcTemplate.queryForObject(sql, Long.class, recipeId);

            if (authorId == null) {
                throw new IllegalArgumentException("Recipe not found: " + recipeId);
            }

            if (authorId != userId) {
                throw new SecurityException("Only the recipe author can delete the recipe");
            }

        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe not found: " + recipeId);
        } catch (Exception e) {
            throw new SecurityException("Failed to validate recipe ownership");
        }
    }

    private void executeCascadeDelete(long recipeId) {
        try {
            String deleteLikesSql = """
            DELETE FROM review_likes 
            WHERE ReviewId IN (SELECT ReviewId FROM reviews WHERE RecipeId = ?)
            """;
            jdbcTemplate.update(deleteLikesSql, recipeId);

            String deleteReviewsSql = "DELETE FROM reviews WHERE RecipeId = ?";
            jdbcTemplate.update(deleteReviewsSql, recipeId);

            String deleteIngredientsSql = "DELETE FROM recipe_ingredients WHERE RecipeId = ?";
            jdbcTemplate.update(deleteIngredientsSql, recipeId);

            String deleteRecipeSql = "DELETE FROM recipes WHERE RecipeId = ?";
            int recipeDeleted = jdbcTemplate.update(deleteRecipeSql, recipeId);

            if (recipeDeleted == 0) {
                throw new IllegalStateException("Recipe was not deleted (possibly already deleted)");
            }

        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete recipe: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void updateTimes(AuthInfo auth, long recipeId, String cookTimeIso, String prepTimeIso) {
        if (auth == null) {
            throw new SecurityException("Authentication information is required");
        }

        if (recipeId <= 0) {
            throw new IllegalArgumentException("Invalid recipe ID: " + recipeId);
        }

        Long userId = validateUserAndGetId(auth);
        if (userId == null) {
            throw new SecurityException("User not found or inactive");
        }

        validateRecipeOwnership(recipeId, userId);

        try {
            Duration cookDuration = null;
            Duration prepDuration = null;

            if (cookTimeIso != null) {
                cookDuration = parseAndValidateDuration(cookTimeIso, "cookTime");
            }

            if (prepTimeIso != null) {
                prepDuration = parseAndValidateDuration(prepTimeIso, "prepTime");
            }

            String totalTimeIso = calculateTotalTime(cookDuration, prepDuration);

            updateRecipeTimes(recipeId, cookTimeIso, prepTimeIso, totalTimeIso);

        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO 8601 duration format: " + e.getMessage(), e);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Duration overflow or negative value: " + e.getMessage(), e);
        }
    }

    private Long validateUserAndGetId(AuthInfo auth) {
        try {
            String sql = "SELECT AuthorId FROM users WHERE AuthorId = ? AND Password = ? AND IsDeleted = FALSE";
            return jdbcTemplate.queryForObject(sql, Long.class, auth.getAuthorId(), auth.getPassword());
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Duration parseAndValidateDuration(String isoDuration, String fieldName) {
        try {
            Duration duration = Duration.parse(isoDuration);

            if (duration.isNegative()) {
                throw new IllegalArgumentException(fieldName + " cannot be negative");
            }

            try {
                duration.toSeconds();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(fieldName + " value too large");
            }

            return duration;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + fieldName + " format: " + isoDuration, e);
        }
    }

    private String calculateTotalTime(Duration cookDuration, Duration prepDuration) {
        Duration totalDuration = Duration.ZERO;

        if (cookDuration != null) {
            totalDuration = totalDuration.plus(cookDuration);
        }

        if (prepDuration != null) {
            totalDuration = totalDuration.plus(prepDuration);
        }

        if (totalDuration.isZero()) {
            return null;
        }

        return totalDuration.toString();
    }

    private void updateRecipeTimes(long recipeId, String cookTimeIso, String prepTimeIso, String totalTimeIso) {
        StringBuilder sqlBuilder = new StringBuilder("UPDATE recipes SET ");
        List<Object> params = new ArrayList<>();
        boolean hasUpdate = false;

        if (cookTimeIso != null) {
            sqlBuilder.append("CookTime = ?");
            params.add(cookTimeIso);
            hasUpdate = true;
        }

        if (prepTimeIso != null) {
            if (hasUpdate) {
                sqlBuilder.append(", ");
            }
            sqlBuilder.append("PrepTime = ?");
            params.add(prepTimeIso);
            hasUpdate = true;
        }

        if (totalTimeIso != null) {
            if (hasUpdate) {
                sqlBuilder.append(", ");
            }
            sqlBuilder.append("TotalTime = ?");
            params.add(totalTimeIso);
            hasUpdate = true;
        }

        if (!hasUpdate) {
            return;
        }

        sqlBuilder.append(" WHERE RecipeId = ?");
        params.add(recipeId);

        String sql = sqlBuilder.toString();

        int rowsUpdated = jdbcTemplate.update(sql, params.toArray());

        if (rowsUpdated == 0) {
            throw new IllegalStateException("Failed to update recipe times. Recipe may have been deleted.");
        }
    }

    @Override
    public Map<String, Object> getClosestCaloriePair() {
        String sql = """
        WITH calorie_data AS (
            SELECT 
                RecipeId,
                Calories,
                LAG(RecipeId) OVER (ORDER BY Calories, RecipeId) AS prev_id,
                LAG(Calories) OVER (ORDER BY Calories, RecipeId) AS prev_cal
            FROM recipes
            WHERE Calories IS NOT NULL
        ),
        differences AS (
            SELECT 
                LEAST(RecipeId, prev_id) AS RecipeA,
                GREATEST(RecipeId, prev_id) AS RecipeB,
                CASE WHEN RecipeId < prev_id THEN Calories ELSE prev_cal END AS CaloriesA,
                CASE WHEN RecipeId < prev_id THEN prev_cal ELSE Calories END AS CaloriesB,
                ABS(Calories - prev_cal) AS diff
            FROM calorie_data
            WHERE prev_id IS NOT NULL
        )
        SELECT 
            RecipeA,
            RecipeB,
            CaloriesA,
            CaloriesB,
            diff AS Difference
        FROM differences
        ORDER BY diff ASC, RecipeA ASC, RecipeB ASC
        LIMIT 1
        """;

        try {
            Map<String, Object> result = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("RecipeA", rs.getLong("RecipeA"));
                map.put("RecipeB", rs.getLong("RecipeB"));
                map.put("CaloriesA", rs.getDouble("CaloriesA"));
                map.put("CaloriesB", rs.getDouble("CaloriesB"));
                map.put("Difference", rs.getDouble("Difference"));
                return map;
            });

            return result;

        } catch (EmptyResultDataAccessException e) {
            String countSql = "SELECT COUNT(*) FROM recipes WHERE Calories IS NOT NULL";
            try {
                jdbcTemplate.queryForObject(countSql, Integer.class);
            } catch (DataAccessException ex) {
                // ignore
            }
            return null;
        } catch (DataAccessException e) {
            throw new RuntimeException("Failed to find closest calorie pair", e);
        }
    }

    @Override
    public List<Map<String, Object>> getTop3MostComplexRecipesByIngredients() {
        String sql = """
        SELECT 
            ri.RecipeId,
            r.Name,
            COUNT(DISTINCT ri.IngredientPart) AS IngredientCount
        FROM recipe_ingredients ri
        JOIN recipes r ON ri.RecipeId = r.RecipeId
        GROUP BY ri.RecipeId, r.Name
        ORDER BY 
            COUNT(DISTINCT ri.IngredientPart) DESC,
            ri.RecipeId ASC
        LIMIT 3
        """;

        try {
            List<Map<String, Object>> result = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> recipeMap = new LinkedHashMap<>();
                recipeMap.put("RecipeId", rs.getLong("RecipeId"));
                recipeMap.put("Name", rs.getString("Name"));
                recipeMap.put("IngredientCount", rs.getInt("IngredientCount"));
                return recipeMap;
            });

            return result;

        } catch (DataAccessException e) {
            throw new RuntimeException("Failed to find top 3 most complex recipes", e);
        }
    }
}