package io.sustc.service.impl;

import io.sustc.dto.ReviewRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.RecipeRecord;
import io.sustc.service.DatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * It's important to mark your implementation class with {@link Service} annotation.
 * As long as the class is annotated and implements the corresponding interface, you can place it under any package.
 */
@Service
@Slf4j
public class DatabaseServiceImpl implements DatabaseService {

    /**
     * Getting a {@link DataSource} instance from the framework, whose connections are managed by HikariCP.
     * <p>
     * Marking a field with {@link Autowired} annotation enables our framework to automatically
     * provide you a well-configured instance of {@link DataSource}.
     * Learn more: <a href="https://www.baeldung.com/spring-dependency-injection">Dependency Injection</a>
     */
    @Autowired
    private DataSource dataSource;

    @Override
    public List<Integer> getGroupMembers() {
        return Arrays.asList(12411754, 12411757);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void importData(
            List<ReviewRecord> reviewRecords,
            List<UserRecord> userRecords,
            List<RecipeRecord> recipeRecords) {

        log.info(" 我的importData方法被调用了！");
        log.info("当前时间: " + new Date());

        log.info("数据质量检查:");
        log.info("  用户记录: 总数={}, 空值={}",
                userRecords.size(),
                userRecords.stream().filter(Objects::isNull).count());
        log.info("  食谱记录: 总数={}, 空值={}",
                recipeRecords.size(),
                recipeRecords.stream().filter(Objects::isNull).count());
        log.info("  评论记录: 总数={}, 空值={}",
                reviewRecords.size(),
                reviewRecords.stream().filter(Objects::isNull).count());

        userRecords = userRecords.stream().filter(Objects::nonNull).collect(Collectors.toList());
        recipeRecords = recipeRecords.stream().filter(Objects::nonNull).collect(Collectors.toList());
        reviewRecords = reviewRecords.stream().filter(Objects::nonNull).collect(Collectors.toList());

        // ddl to create tables.
        createTables();

        setupDatabaseUsersAndPrivileges();

        // 按顺序导入数据
        importUserRecords(userRecords);
        importRecipeRecords(recipeRecords);
        importReviewRecords(reviewRecords);
        importUserFollows(userRecords);
        importReviewLikes(reviewRecords);
        importRecipeIngredients(recipeRecords);
    }

    /**
     * 权限管理：按最小权限原则创建 3 个数据库角色，并授予相应权限。
     * 参考报告中的权限实现 SQL：CREATE USER / GRANT CONNECT / GRANT USAGE / 表权限 / 默认权限 / REVOKE PUBLIC 等
     */
    private void setupDatabaseUsersAndPrivileges() {
        final long start = System.currentTimeMillis();
        log.info("开始初始化数据库用户与权限...");

        String dbName = jdbcTemplate.queryForObject("SELECT current_database()", String.class);

        final String analyst = "sustc_analyst";
        final String app = "sustc_app";
        final String admin = "sustc_admin";

        final String analystPwd = "analyst_secure123";
        final String appPwd = "app_secure123";
        final String adminPwd = "admin_secure123";

        // 1) 若已存在：先删后建（关键步骤：失败直接抛异常）
        for (String role : Arrays.asList(analyst, app, admin)) {
            String dropIfExists = ""
                    + "DO $$\n"
                    + "BEGIN\n"
                    + "  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + role + "') THEN\n"
                    + "    EXECUTE 'REASSIGN OWNED BY " + role + " TO ' || current_user;\n"
                    + "    EXECUTE 'DROP OWNED BY " + role + "';\n"
                    + "    EXECUTE 'DROP ROLE " + role + "';\n"
                    + "  END IF;\n"
                    + "END $$;";
            execDcl(dropIfExists, "删除已存在角色: " + role, false);
        }

        // 2) 创建用户（关键步骤）
        execDcl("CREATE USER " + analyst + " WITH PASSWORD '" + analystPwd + "'", "创建用户: " + analyst, false);
        execDcl("CREATE USER " + app + " WITH PASSWORD '" + appPwd + "'", "创建用户: " + app, false);
        execDcl("CREATE USER " + admin + " WITH PASSWORD '" + adminPwd + "'", "创建用户: " + admin, false);

        // 3) GRANT CONNECT / USAGE（关键步骤）
        execDcl("GRANT CONNECT ON DATABASE " + dbName + " TO " + analyst + ", " + app + ", " + admin,
                "授予 CONNECT(db=" + dbName + ")", false);
        execDcl("GRANT USAGE ON SCHEMA public TO " + analyst + ", " + app + ", " + admin,
                "授予 USAGE(schema=public)", false);

        // 4) 表级权限（关键步骤）
        execDcl("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + analyst,
                "analyst: SELECT(all tables)", false);
        execDcl("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + app,
                "app: CRUD(all tables)", false);
        execDcl("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO " + admin,
                "admin: ALL(all tables)", false);

        // 5) 默认权限（关键步骤）
        execDcl("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + analyst,
                "默认权限: analyst SELECT", false);
        execDcl("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + app,
                "默认权限: app CRUD", false);
        execDcl("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO " + admin,
                "默认权限: admin ALL", false);

        // 6) “优化项”：精细化控制 + 回收公共权限（这些在某些环境下可能因权限/对象不存在等原因失败，可选择容错）
        // 你们报告里就是用 try/catch 记录 warn 来容错 :contentReference[oaicite:2]{index=2}
        execDcl("GRANT INSERT ON public.reviews TO " + app,
                "精细化控制: app INSERT reviews", true);
        execDcl("GRANT UPDATE ON public.recipes TO " + app,
                "精细化控制: app UPDATE recipes", true);

        execDcl("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC",
                "回收公共权限: REVOKE table FROM PUBLIC", true);
        execDcl("REVOKE USAGE ON SCHEMA public FROM PUBLIC",
                "回收公共权限: REVOKE schema USAGE FROM PUBLIC", true);

        log.info("数据库用户与权限初始化完成（耗时 {} ms）", (System.currentTimeMillis() - start));
    }

    /**
     * 权限相关 SQL 的统一执行器：带日志 + 可控容错。
     * 参考报告“错误处理与日志”的建议：失败时 log.warn 记录原因 :contentReference[oaicite:3]{index=3}
     *
     * @param ignoreError true: 失败仅 warn 并继续；false: 失败 error 并抛异常（触发 @Transactional 回滚）
     */
    private void execDcl(String sql, String action, boolean ignoreError) {
        try {
            jdbcTemplate.execute(sql);
            log.info(" {} 成功", action);
        } catch (Exception e) {
            if (ignoreError) {
                log.warn(" {} 未执行/可能已存在/可忽略：{}", action, e.getMessage());
            } else {
                log.error(" {} 失败，终止导入。SQL={}", action, sql, e);
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            }
        }
    }


    private void importUserRecords(List<UserRecord> userRecords) {
        if (userRecords.isEmpty()) {
            log.warn("没有用户记录需要导入");
            return;
        }

        String sql = "INSERT INTO users (AuthorId, AuthorName, Gender, Age, Followers, Following, Password, IsDeleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        // 使用JdbcTemplate的批量更新功能
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                UserRecord user = userRecords.get(i);
                ps.setLong(1, user.getAuthorId());
                ps.setString(2, user.getAuthorName());
                ps.setString(3, user.getGender());
                ps.setInt(4, user.getAge());
                ps.setInt(5, user.getFollowers());
                ps.setInt(6, user.getFollowing());
                ps.setString(7, user.getPassword());
                ps.setBoolean(8, user.isDeleted());
            }

            @Override
            public int getBatchSize() {
                return userRecords.size();
            }
        });

        log.info("批量导入 {} 条用户记录", userRecords.size());
    }

    private void importRecipeRecords(List<RecipeRecord> recipeRecords) {
        if (recipeRecords.isEmpty()) {
            log.warn("没有食谱记录需要导入");
            return;
        }

        String sql = "INSERT INTO recipes (" +
                "RecipeId, Name, AuthorId, CookTime, PrepTime, TotalTime, " +
                "DatePublished, Description, RecipeCategory, AggregatedRating, " +
                "ReviewCount, Calories, FatContent, SaturatedFatContent, " +
                "CholesterolContent, SodiumContent, CarbohydrateContent, " +
                "FiberContent, SugarContent, ProteinContent, RecipeServings, RecipeYield" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RecipeRecord recipe = recipeRecords.get(i);
                int paramIndex = 1;
                ps.setLong(paramIndex++, recipe.getRecipeId());
                ps.setString(paramIndex++, recipe.getName());
                ps.setLong(paramIndex++, recipe.getAuthorId());
                ps.setString(paramIndex++, recipe.getCookTime());
                ps.setString(paramIndex++, recipe.getPrepTime());
                ps.setString(paramIndex++, recipe.getTotalTime());
                ps.setTimestamp(paramIndex++, recipe.getDatePublished());
                ps.setString(paramIndex++, recipe.getDescription());
                ps.setString(paramIndex++, recipe.getRecipeCategory());
                ps.setFloat(paramIndex++, recipe.getAggregatedRating());
                ps.setInt(paramIndex++, recipe.getReviewCount());
                ps.setFloat(paramIndex++, recipe.getCalories());
                ps.setFloat(paramIndex++, recipe.getFatContent());
                ps.setFloat(paramIndex++, recipe.getSaturatedFatContent());
                ps.setFloat(paramIndex++, recipe.getCholesterolContent());
                ps.setFloat(paramIndex++, recipe.getSodiumContent());
                ps.setFloat(paramIndex++, recipe.getCarbohydrateContent());
                ps.setFloat(paramIndex++, recipe.getFiberContent());
                ps.setFloat(paramIndex++, recipe.getSugarContent());
                ps.setFloat(paramIndex++, recipe.getProteinContent());
                ps.setInt(paramIndex++, recipe.getRecipeServings());
                ps.setString(paramIndex++, recipe.getRecipeYield());
            }

            @Override
            public int getBatchSize() {
                return recipeRecords.size();
            }
        });

        log.info("批量导入 {} 条食谱记录", recipeRecords.size());
    }

    private void importReviewRecords(List<ReviewRecord> reviewRecords) {
        if (reviewRecords.isEmpty()) {
            log.warn("没有评论记录需要导入");
            return;
        }

        String sql = "INSERT INTO reviews (" +
                "ReviewId, RecipeId, AuthorId, Rating, Review, " +
                "DateSubmitted, DateModified" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ReviewRecord review = reviewRecords.get(i);
                ps.setLong(1, review.getReviewId());
                ps.setLong(2, review.getRecipeId());
                ps.setLong(3, review.getAuthorId());
                ps.setFloat(4, review.getRating());
                ps.setString(5, review.getReview());
                ps.setTimestamp(6, review.getDateSubmitted());
                ps.setTimestamp(7, review.getDateModified());
            }

            @Override
            public int getBatchSize() {
                return reviewRecords.size();
            }
        });

        log.info("批量导入 {} 条评论记录", reviewRecords.size());
    }

    private void importRecipeIngredients(List<RecipeRecord> recipeRecords) {
        if (recipeRecords.isEmpty()) {
            log.warn("没有食谱记录需要导入食材");
            return;
        }

        // 收集所有食材
        List<Object[]> batchArgs = new ArrayList<>();
        Set<String> uniqueIngredients = new HashSet<>();

        for (RecipeRecord recipe : recipeRecords) {
            if (recipe == null || recipe.getRecipeId() <= 0) continue;

            String[] ingredients = recipe.getRecipeIngredientParts();
            if (ingredients == null) continue;

            for (String rawIngredient : ingredients) {
                if (rawIngredient == null) continue;

                String cleanedIngredient = cleanIngredient(rawIngredient);
                if (cleanedIngredient.isEmpty()) continue;

                // 创建唯一键（标准化）
                String uniqueKey = recipe.getRecipeId() + "|" + cleanedIngredient.toLowerCase();

                if (uniqueIngredients.add(uniqueKey)) {
                    batchArgs.add(new Object[]{recipe.getRecipeId(), cleanedIngredient});
                }
            }
        }

        if (!batchArgs.isEmpty()) {
            String sql = "INSERT INTO recipe_ingredients (RecipeId, IngredientPart) VALUES (?, ?)";
            jdbcTemplate.batchUpdate(sql, batchArgs);
            log.info("批量导入 {} 条食材记录", batchArgs.size());
        }
    }

    // 辅助方法：清理食材字符串
    private String cleanIngredient(String ingredient) {
        if (ingredient == null) return "";
        return ingredient.trim().replaceAll("\\s+", " ");
    }

    private void importUserFollows(List<UserRecord> userRecords) {
        if (userRecords.isEmpty()) {
            log.warn("没有用户记录需要导入关注关系");
            return;
        }

        // 收集所有关注关系
        Set<String> uniqueRelationships = new HashSet<>();
        List<Object[]> batchArgs = new ArrayList<>();

        for (UserRecord user : userRecords) {
            if (user == null) continue;

            long currentUserId = user.getAuthorId();

            // 处理粉丝（关注当前用户的人）
            if (user.getFollowerUsers() != null) {
                for (long followerId : user.getFollowerUsers()) {
                    if (followerId <= 0 || followerId == currentUserId) continue;

                    String relationshipKey = followerId + "-" + currentUserId;
                    if (uniqueRelationships.add(relationshipKey)) {
                        batchArgs.add(new Object[]{followerId, currentUserId});
                    }
                }
            }

            // 处理当前用户关注的人
            if (user.getFollowingUsers() != null) {
                for (long followingId : user.getFollowingUsers()) {
                    if (followingId <= 0 || followingId == currentUserId) continue;

                    String relationshipKey = currentUserId + "-" + followingId;
                    if (uniqueRelationships.add(relationshipKey)) {
                        batchArgs.add(new Object[]{currentUserId, followingId});
                    }
                }
            }
        }

        if (!batchArgs.isEmpty()) {
            String sql = "INSERT INTO user_follows (FollowerId, FollowingId) VALUES (?, ?)";
            jdbcTemplate.batchUpdate(sql, batchArgs);
            log.info("批量导入 {} 条关注关系", batchArgs.size());
        }
    }

    private void importReviewLikes(List<ReviewRecord> reviewRecords) {
        if (reviewRecords.isEmpty()) {
            log.warn("没有评论记录需要导入点赞");
            return;
        }

        // 收集所有点赞
        List<Object[]> batchArgs = new ArrayList<>();

        for (ReviewRecord review : reviewRecords) {
            if (review.getLikes() != null) {
                for (long userId : review.getLikes()) {
                    batchArgs.add(new Object[]{review.getReviewId(), userId});
                }
            }
        }

        if (!batchArgs.isEmpty()) {
            String sql = "INSERT INTO review_likes (ReviewId, AuthorId) VALUES (?, ?)";
            jdbcTemplate.batchUpdate(sql, batchArgs);
            log.info("批量导入 {} 条点赞记录", batchArgs.size());
        }
    }

    private void createTables() {
        String[] createTableSQLs = {
                // 创建users表
                "CREATE TABLE IF NOT EXISTS users (" +
                        "    AuthorId BIGINT PRIMARY KEY, " +
                        "    AuthorName VARCHAR(255) NOT NULL, " +
                        "    Gender VARCHAR(10) CHECK (Gender IN ('Male', 'Female')), " +
                        "    Age INTEGER CHECK (Age > 0), " +
                        "    Followers INTEGER DEFAULT 0 CHECK (Followers >= 0), " +
                        "    Following INTEGER DEFAULT 0 CHECK (Following >= 0), " +
                        "    Password VARCHAR(255), " +
                        "    IsDeleted BOOLEAN DEFAULT FALSE" +
                        ")",

                // 创建recipes表
                "CREATE TABLE IF NOT EXISTS recipes (" +
                        "    RecipeId BIGINT PRIMARY KEY, " +
                        "    Name VARCHAR(500) NOT NULL, " +
                        "    AuthorId BIGINT NOT NULL, " +
                        "    CookTime VARCHAR(50), " +
                        "    PrepTime VARCHAR(50), " +
                        "    TotalTime VARCHAR(50), " +
                        "    DatePublished TIMESTAMP, " +
                        "    Description TEXT, " +
                        "    RecipeCategory VARCHAR(255), " +
                        "    AggregatedRating DECIMAL(3,2) CHECK (AggregatedRating >= 0 AND AggregatedRating <= 5), " +
                        "    ReviewCount INTEGER DEFAULT 0 CHECK (ReviewCount >= 0), " +
                        "    Calories DECIMAL(10,2), " +
                        "    FatContent DECIMAL(10,2), " +
                        "    SaturatedFatContent DECIMAL(10,2), " +
                        "    CholesterolContent DECIMAL(10,2), " +
                        "    SodiumContent DECIMAL(10,2), " +
                        "    CarbohydrateContent DECIMAL(10,2), " +
                        "    FiberContent DECIMAL(10,2), " +
                        "    SugarContent DECIMAL(10,2), " +
                        "    ProteinContent DECIMAL(10,2), " +
                        "    RecipeServings VARCHAR(100), " +
                        "    RecipeYield VARCHAR(100), " +
                        "    FOREIGN KEY (AuthorId) REFERENCES users(AuthorId)" +
                        ")",

                // 创建reviews表
                "CREATE TABLE IF NOT EXISTS reviews (" +
                        "    ReviewId BIGINT PRIMARY KEY, " +
                        "    RecipeId BIGINT NOT NULL, " +
                        "    AuthorId BIGINT NOT NULL, " +
                        "    Rating DECIMAL(3,2), " +
                        "    Review TEXT, " +
                        "    DateSubmitted TIMESTAMP, " +
                        "    DateModified TIMESTAMP, " +
                        "    FOREIGN KEY (RecipeId) REFERENCES recipes(RecipeId), " +
                        "    FOREIGN KEY (AuthorId) REFERENCES users(AuthorId)" +
                        ")",

                // 创建recipe_ingredients表
                "CREATE TABLE IF NOT EXISTS recipe_ingredients (" +
                        "    RecipeId BIGINT, " +
                        "    IngredientPart VARCHAR(500), " +
                        "    PRIMARY KEY (RecipeId, IngredientPart), " +
                        "    FOREIGN KEY (RecipeId) REFERENCES recipes(RecipeId)" +
                        ")",

                // 创建review_likes表
                "CREATE TABLE IF NOT EXISTS review_likes (" +
                        "    ReviewId BIGINT, " +
                        "    AuthorId BIGINT, " +
                        "    PRIMARY KEY (ReviewId, AuthorId), " +
                        "    FOREIGN KEY (ReviewId) REFERENCES reviews(ReviewId), " +
                        "    FOREIGN KEY (AuthorId) REFERENCES users(AuthorId)" +
                        ")",

                // 创建user_follows表
                "CREATE TABLE IF NOT EXISTS user_follows (" +
                        "    FollowerId BIGINT, " +
                        "    FollowingId BIGINT, " +
                        "    PRIMARY KEY (FollowerId, FollowingId), " +
                        "    FOREIGN KEY (FollowerId) REFERENCES users(AuthorId), " +
                        "    FOREIGN KEY (FollowingId) REFERENCES users(AuthorId), " +
                        "    CHECK (FollowerId != FollowingId)" +
                        ")"
        };

        for (String sql : createTableSQLs) {
            jdbcTemplate.execute(sql);
        }

        log.info("数据库表创建完成");
    }

    private int getTableCount(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /*
     * The following code is just a quick example of using jdbc datasource.
     * Practically, the code interacts with database is usually written in a DAO layer.
     *
     * Reference: [Data Access Object pattern](https://www.baeldung.com/java-dao-pattern)
     */

    @Override
    public void drop() {
        // You can use the default drop script provided by us in most cases,
        // but if it doesn't work properly, you may need to modify it.
        // This method will delete all the tables in the public schema.

        String sql = "DO $$\n" +
                "DECLARE\n" +
                "    tables CURSOR FOR\n" +
                "        SELECT tablename\n" +
                "        FROM pg_tables\n" +
                "        WHERE schemaname = 'public';\n" +
                "BEGIN\n" +
                "    FOR t IN tables\n" +
                "    LOOP\n" +
                "        EXECUTE 'DROP TABLE IF EXISTS ' || QUOTE_IDENT(t.tablename) || ' CASCADE;';\n" +
                "    END LOOP;\n" +
                "END $$;\n";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
            log.info("数据库表删除完成");
        } catch (SQLException e) {
            log.error("删除数据库表失败", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Integer sum(int a, int b) {
        String sql = "SELECT ?+?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, a);
            stmt.setInt(2, b);
            log.info("SQL: {}", stmt);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}