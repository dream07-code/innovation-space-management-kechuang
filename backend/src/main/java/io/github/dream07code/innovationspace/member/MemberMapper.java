package io.github.dream07code.innovationspace.member;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberMapper {
  @Select(
      "SELECT m.*,u.id AS user_id,u.role,u.status AS account_status,u.must_change_password "
          + "FROM member m JOIN app_user u ON u.member_id=m.id WHERE m.id=#{id}")
  Map<String, Object> find(@Param("id") long id);

  @Select(
      """
<script>
SELECT m.*,u.id AS user_id,u.role,u.status AS account_status,u.must_change_password
FROM member m JOIN app_user u ON u.member_id=m.id
WHERE 1=1
<if test="!admin">
  AND u.role='STUDENT'
  <choose>
    <when test="scopes.size()>0">
      AND m.direction_id IN
      <foreach collection="scopes" item="d" open="(" separator="," close=")">#{d}</foreach>
    </when>
    <otherwise>AND 1=0</otherwise>
  </choose>
</if>
<if test="direction != null">AND m.direction_id=#{direction}</if>
<if test="keyword != null">AND (m.name LIKE CONCAT('%',#{keyword},'%') OR m.student_no LIKE CONCAT('%',#{keyword},'%'))</if>
<if test="status != null">AND m.membership_status=#{status}</if>
ORDER BY m.created_at DESC,m.id DESC
</script>
""")
  Page<Map<String, Object>> list(
      Page<Map<String, Object>> page,
      @Param("admin") boolean admin,
      @Param("scopes") List<Long> scopes,
      @Param("direction") Long direction,
      @Param("keyword") String keyword,
      @Param("status") String status);
}
