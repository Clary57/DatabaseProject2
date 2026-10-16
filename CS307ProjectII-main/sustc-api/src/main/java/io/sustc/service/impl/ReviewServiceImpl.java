package io.sustc.service.impl;

import io.fury.util.LoggerFactory;
import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RecipeRecord;
import io.sustc.dto.ReviewRecord;
import io.sustc.service.ReviewService;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Isolation;

import javax.annotation.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
//@Slf4j
public class ReviewServiceImpl implements ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewServiceImpl.class);

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证用户是否有效且活跃
     */
    private void validateUser(AuthInfo auth) {
        if (auth == null || auth.getAuthorId() <= 0) {
            throw new IllegalArgumentException("Invalid authentication information");
        }

        // 检查用户是否存在且未被删除
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE AuthorId = ? AND IsDeleted = FALSE",
                Integer.class,
                auth.getAuthorId()
        );

        if (count == null || count == 0) {
            throw new SecurityException("User is invalid or inactive");
        }
    }

    /**
     * 验证食谱是否存在且未被删除
     */
    private void validateRecipe(long recipeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recipes WHERE RecipeId = ?",
                Integer.class,
                recipeId
        );

        if (count == null || count == 0) {
            throw new IllegalArgumentException("Recipe does not exist");
        }
    }

    /**
     * 验证评分是否在有效范围内
     */
    private void validateRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
    }




    // 未实现提升事务隔离级别为SERIALIZABLE，避免并发查询冲突
    @Override
    @Transactional
    public long addReview(AuthInfo auth, long recipeId, int rating, String review) {
        // 1. 验证 auth 不为 null
        if (auth == null) {
            throw new IllegalArgumentException("AuthInfo cannot be null");
        }

        // 2. 验证 rating 范围
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        // 3. 验证用户身份（包括密码验证）
        long loginResult = userService.login(auth);
        if (loginResult == -1) {
            throw new SecurityException("User authentication failed");
        }

        // 4. 验证食谱是否存在
        try {
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM recipes WHERE RecipeId = ?",
                    Integer.class,
                    recipeId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe does not exist", e);
        }

        // 5. 生成新的 ReviewId（获取当前最大ID并加1）
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(ReviewId), 0) FROM reviews",
                Long.class
        );
        long newReviewId = maxId + 1;

        // 6. 获取用户名
        String authorName = jdbcTemplate.queryForObject(
                "SELECT AuthorName FROM users WHERE AuthorId = ?",
                String.class,
                auth.getAuthorId()
        );

        // 7. 获取当前时间
        Timestamp now = new Timestamp(System.currentTimeMillis());

        // 8. 插入新评论
        jdbcTemplate.update(
                "INSERT INTO reviews (ReviewId, RecipeId, AuthorId, Rating, Review, DateSubmitted, DateModified) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                newReviewId,
                recipeId,
                auth.getAuthorId(),
                rating,
                review,
                now,
                now
        );

        // 9. 更新食谱的统计信息
        try {
            refreshRecipeAggregatedRating(recipeId);
        } catch (IllegalArgumentException e) {
            // 如果食谱被删除，回滚事务
            throw new IllegalArgumentException("Recipe does not exist or is deleted", e);
        }

        // 记录日志
        //log.info("Added review {} for recipe {} by user {}", newReviewId, recipeId, auth.getAuthorId());

        return newReviewId;
    }


    @Override
    @Transactional
    public void editReview(AuthInfo auth, long recipeId, long reviewId, int rating, String review) {
        // 验证参数
        validateUser(auth);
        validateRecipe(recipeId);
        validateRating(rating);

        // 检查评论是否属于该食谱
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE ReviewId = ? AND RecipeId = ?",
                Integer.class,
                reviewId, recipeId
        );

        if (count == null || count == 0) {
            throw new IllegalArgumentException("Review does not belong to the recipe");
        }

        // 检查是否为评论作者
        Long authorId = jdbcTemplate.queryForObject(
                "SELECT AuthorId FROM reviews WHERE ReviewId = ?",
                Long.class,
                reviewId
        );

        if (authorId == null || authorId != auth.getAuthorId()) {
            throw new SecurityException("Not the author of the review");
        }

        // 更新评论
        jdbcTemplate.update(
                "UPDATE reviews SET Rating = ?, Review = ?, DateModified = ? WHERE ReviewId = ?",
                rating, review, Timestamp.from(Instant.now()), reviewId
        );

        // 刷新食谱评分
        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public void deleteReview(AuthInfo auth, long recipeId, long reviewId) {
        // 验证用户
        validateUser(auth);

        // 检查评论是否属于该食谱
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE ReviewId = ? AND RecipeId = ?",
                Integer.class,
                reviewId, recipeId
        );

        if (count == null || count == 0) {
            throw new IllegalArgumentException("Review does not belong to the recipe");
        }

        // 检查是否有权限删除（作者）
        Long authorId = jdbcTemplate.queryForObject(
                "SELECT AuthorId FROM reviews WHERE ReviewId = ?",
                Long.class,
                reviewId
        );

        if (authorId == null || authorId != auth.getAuthorId()) {
            throw new SecurityException("Not authorized to delete this review");
        }

        // 删除评论的点赞
        jdbcTemplate.update(
                "DELETE FROM review_likes WHERE ReviewId = ?",
                reviewId
        );

        // 删除评论
        jdbcTemplate.update(
                "DELETE FROM reviews WHERE ReviewId = ?",
                reviewId
        );

        // 刷新食谱评分（使用修改后的逻辑）
        refreshRecipeAggregatedRating(recipeId);
    }

    @Override
    @Transactional
    public long likeReview(AuthInfo auth, long reviewId) {
        // 1. 验证 auth 不为空
        if (auth == null) {
            throw new SecurityException("AuthInfo cannot be null");
        }

        // 2. 验证用户存在、活跃且密码正确
        String checkUserSql = "SELECT Password FROM users WHERE AuthorId = ? AND IsDeleted = false";
        String storedPassword;
        try {
            storedPassword = jdbcTemplate.queryForObject(checkUserSql, String.class, auth.getAuthorId());
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User is invalid or inactive");
        }

        // 验证密码
        if (storedPassword == null || !storedPassword.equals(auth.getPassword())) {
            throw new SecurityException("Password incorrect");
        }

        // 3. 验证评论存在
        String checkReviewSql = "SELECT AuthorId FROM reviews WHERE ReviewId = ?";
        Long reviewAuthorId;
        try {
            reviewAuthorId = jdbcTemplate.queryForObject(checkReviewSql, Long.class, reviewId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review does not exist");
        }

        // 4. 验证用户不能给自己的评论点赞
        if (reviewAuthorId != null && reviewAuthorId == auth.getAuthorId()) {
            throw new SecurityException("Users cannot like their own reviews");
        }

        // 5. 检查是否已经点赞过
        String checkLikeSql = "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ? AND AuthorId = ?";
        Integer likeCount = jdbcTemplate.queryForObject(checkLikeSql, Integer.class, reviewId, auth.getAuthorId());

        // 6. 如果未点赞，则插入点赞记录
        if (likeCount == null || likeCount == 0) {
            String insertLikeSql = "INSERT INTO review_likes (ReviewId, AuthorId) VALUES (?, ?)";
            try {
                jdbcTemplate.update(insertLikeSql, reviewId, auth.getAuthorId());
            } catch (Exception e) {
                // 处理可能的重复插入
                //log.warn("Failed to insert like record: {}", e.getMessage());
            }
        }

        // 7. 返回当前总点赞数
        String countLikesSql = "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?";
        Long totalLikes = jdbcTemplate.queryForObject(countLikesSql, Long.class, reviewId);

        return totalLikes != null ? totalLikes : 0L;
    }



    @Override
    @Transactional
    public long unlikeReview(AuthInfo auth, long reviewId) {
        // 1. 验证 auth 不为空
        if (auth == null) {
            throw new SecurityException("AuthInfo cannot be null");
        }

        // 2. 验证用户存在、活跃且密码正确
        String checkUserSql = "SELECT Password FROM users WHERE AuthorId = ? AND IsDeleted = false";
        String storedPassword;
        try {
            storedPassword = jdbcTemplate.queryForObject(checkUserSql, String.class, auth.getAuthorId());
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User is invalid or inactive");
        }

        // 验证密码
        if (storedPassword == null || !storedPassword.equals(auth.getPassword())) {
            throw new SecurityException("Password incorrect");
        }

        // 3. 验证评论存在
        String checkReviewSql = "SELECT COUNT(*) FROM reviews WHERE ReviewId = ?";
        Integer reviewCount = jdbcTemplate.queryForObject(checkReviewSql, Integer.class, reviewId);
        if (reviewCount == null || reviewCount == 0) {
            throw new IllegalArgumentException("Review does not exist");
        }

        // 4. 检查是否已经点赞过
        String checkLikeSql = "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ? AND AuthorId = ?";
        Integer likeCount = jdbcTemplate.queryForObject(checkLikeSql, Integer.class, reviewId, auth.getAuthorId());

        // 5. 如果已点赞，则删除点赞记录
        if (likeCount != null && likeCount > 0) {
            String deleteLikeSql = "DELETE FROM review_likes WHERE ReviewId = ? AND AuthorId = ?";
            jdbcTemplate.update(deleteLikeSql, reviewId, auth.getAuthorId());
        }
        // 如果没有点赞，什么都不做（no-op）

        // 6. 返回当前总点赞数
        String countLikesSql = "SELECT COUNT(*) FROM review_likes WHERE ReviewId = ?";
        Long totalLikes = jdbcTemplate.queryForObject(countLikesSql, Long.class, reviewId);

        return totalLikes != null ? totalLikes : 0L;
    }


    @Override
    public PageResult<ReviewRecord> listByRecipe(long recipeId, int page, int size, String sort) {
        // 参数验证（保持不变）
        if (page < 1) throw new IllegalArgumentException("Page number must be >= 1");
        if (size <= 0) throw new IllegalArgumentException("Page size must be positive");
        if (!"date_desc".equals(sort) && !"likes_desc".equals(sort)) {
            throw new IllegalArgumentException("Sort option must be 'date_desc' or 'likes_desc'");
        }

        // 检查食谱是否存在
        String recipeCheckSql = "SELECT COUNT(*) FROM recipes WHERE RecipeId = ?";
        Integer recipeCount = jdbcTemplate.queryForObject(recipeCheckSql, Integer.class, recipeId);
        if (recipeCount == null || recipeCount == 0) {
            // 根据接口文档，应该返回空结果而不是异常？
            // 但你的实现抛异常，需要确认需求
            throw new IllegalArgumentException("Recipe with id " + recipeId + " does not exist");
        }

        // 计算分页
        int offset = (page - 1) * size;

        // 使用窗口函数一次性查询所有需要的数据
        String sql;
        if ("date_desc".equals(sort)) {
            sql = """
            WITH ranked_reviews AS (
                SELECT 
                    r.ReviewId,
                    r.RecipeId,
                    r.AuthorId,
                    u.AuthorName,
                    r.Rating,
                    r.Review,
                    r.DateSubmitted,
                    r.DateModified,
                    COUNT(rl.AuthorId) as like_count,
                    ROW_NUMBER() OVER (ORDER BY r.DateModified DESC) as rn
                FROM reviews r
                JOIN users u ON r.AuthorId = u.AuthorId
                LEFT JOIN review_likes rl ON r.ReviewId = rl.ReviewId
                WHERE r.RecipeId = ? AND u.IsDeleted = FALSE
                GROUP BY r.ReviewId, r.RecipeId, r.AuthorId, u.AuthorName, 
                         r.Rating, r.Review, r.DateSubmitted, r.DateModified
            )
            SELECT * FROM ranked_reviews WHERE rn > ? AND rn <= ?
            ORDER BY rn
            """;
        } else { // likes_desc
            sql = """
            WITH ranked_reviews AS (
                SELECT 
                    r.ReviewId,
                    r.RecipeId,
                    r.AuthorId,
                    u.AuthorName,
                    r.Rating,
                    r.Review,
                    r.DateSubmitted,
                    r.DateModified,
                    COUNT(rl.AuthorId) as like_count,
                    ROW_NUMBER() OVER (ORDER BY COUNT(rl.AuthorId) DESC, r.DateModified DESC) as rn
                FROM reviews r
                JOIN users u ON r.AuthorId = u.AuthorId
                LEFT JOIN review_likes rl ON r.ReviewId = rl.ReviewId
                WHERE r.RecipeId = ? AND u.IsDeleted = FALSE
                GROUP BY r.ReviewId, r.RecipeId, r.AuthorId, u.AuthorName, 
                         r.Rating, r.Review, r.DateSubmitted, r.DateModified
            )
            SELECT * FROM ranked_reviews WHERE rn > ? AND rn <= ?
            ORDER BY rn
            """;
        }

        // 执行查询
        List<ReviewRecord> reviewRecords = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    ReviewRecord record = new ReviewRecord();
                    record.setReviewId(rs.getLong("ReviewId"));
                    record.setRecipeId(rs.getLong("RecipeId"));
                    record.setAuthorId(rs.getLong("AuthorId"));
                    record.setAuthorName(rs.getString("AuthorName"));
                    record.setRating(rs.getFloat("Rating"));
                    record.setReview(rs.getString("Review"));
                    record.setDateSubmitted(rs.getTimestamp("DateSubmitted"));
                    record.setDateModified(rs.getTimestamp("DateModified"));
                    return record;
                },
                recipeId, offset, offset + size
        );

        // 批量查询所有点赞用户（避免N+1）
        if (!reviewRecords.isEmpty()) {
            List<Long> reviewIds = reviewRecords.stream()
                    .map(ReviewRecord::getReviewId)
                    .collect(Collectors.toList());

            String placeholders = String.join(",",
                    Collections.nCopies(reviewIds.size(), "?"));

            String likesSql = String.format("""
            SELECT ReviewId, AuthorId 
            FROM review_likes 
            WHERE ReviewId IN (%s)
            ORDER BY ReviewId, AuthorId
            """, placeholders);

            // 执行批量查询
            List<Map<String, Object>> likesResults = jdbcTemplate.queryForList(
                    likesSql, reviewIds.toArray());

            // 按ReviewId分组
            Map<Long, List<Long>> likesByReview = likesResults.stream()
                    .collect(Collectors.groupingBy(
                            row -> ((Number) row.get("reviewid")).longValue(),
                            Collectors.mapping(
                                    row -> ((Number) row.get("authorid")).longValue(),
                                    Collectors.toList()
                            )
                    ));

            // 设置点赞数组
            for (ReviewRecord review : reviewRecords) {
                List<Long> likes = likesByReview.getOrDefault(review.getReviewId(),
                        Collections.emptyList());
                long[] likesArray = likes.stream().mapToLong(Long::longValue).toArray();
                review.setLikes(likesArray);
            }
        }

        // 查询总数
        String countSql = """
        SELECT COUNT(*) 
        FROM reviews r
        JOIN users u ON r.AuthorId = u.AuthorId
        WHERE r.RecipeId = ? AND u.IsDeleted = FALSE
        """;

        Long total = jdbcTemplate.queryForObject(countSql, Long.class, recipeId);
        if (total == null) total = 0L;

        return PageResult.<ReviewRecord>builder()
                .items(reviewRecords)
                .page(page)
                .size(size)
                .total(total)
                .build();
    }

    @Override
    @Transactional
    public RecipeRecord refreshRecipeAggregatedRating(long recipeId) {
        // 检查食谱是否存在
        try {
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM recipes WHERE RecipeId = ?",
                    Integer.class,
                    recipeId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe does not exist or is deleted", e);
        }

        // 计算平均评分和评论数（使用 COALESCE 确保平均值不为 null）
        String sql = "SELECT " +
                "COALESCE(AVG(Rating), 0) as avg_rating, " +  // 关键修改：用 COALESCE 将 null 转为 0
                "COUNT(*) as review_count " +
                "FROM reviews " +
                "WHERE RecipeId = ?";

        List<Map<String, Object>> avgResult = jdbcTemplate.queryForList(sql, recipeId);
        if (avgResult.isEmpty()) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        Map<String, Object> resultMap = avgResult.get(0);
        BigDecimal avgRatingBigDecimal = (BigDecimal) resultMap.get("avg_rating");
        // 此时 avgRatingBigDecimal 不可能为 null，无需处理 null 情况
        Double avgRating = avgRatingBigDecimal.doubleValue();
        Long reviewCount = (Long) resultMap.get("review_count");

        // 保留两位小数
        avgRating = Math.round(avgRating * 100.0) / 100.0;

        // 更新食谱统计信息
        jdbcTemplate.update(
                "UPDATE recipes " +
                        "SET AggregatedRating = ?, ReviewCount = ? " +
                        "WHERE RecipeId = ?",
                avgRating, reviewCount, recipeId
        );

        // Get updated recipe information
        RecipeRecord recipe = jdbcTemplate.queryForObject(
                "SELECT r.RecipeId, r.Name, r.AuthorId, u.AuthorName, " +
                        "r.CookTime, r.PrepTime, r.TotalTime, r.DatePublished, " +
                        "r.Description, r.RecipeCategory, r.AggregatedRating, " +
                        "r.ReviewCount, r.Calories, r.FatContent, r.SaturatedFatContent, " +
                        "r.CholesterolContent, r.SodiumContent, r.CarbohydrateContent, " +
                        "r.FiberContent, r.SugarContent, r.ProteinContent, " +
                        "r.RecipeServings, r.RecipeYield " +
                        "FROM recipes r " +
                        "JOIN users u ON r.AuthorId = u.AuthorId " +
                        "WHERE r.RecipeId = ?",
                new BeanPropertyRowMapper<>(RecipeRecord.class),
                recipeId
        );

        // Get and sort ingredients (case-insensitive)
        List<String> ingredients = jdbcTemplate.queryForList(
                "SELECT IngredientPart FROM recipe_ingredients WHERE RecipeId = ? ORDER BY LOWER(IngredientPart)",
                String.class,
                recipeId
        );
        recipe.setRecipeIngredientParts(ingredients.toArray(new String[0]));

        return recipe;
    }

}