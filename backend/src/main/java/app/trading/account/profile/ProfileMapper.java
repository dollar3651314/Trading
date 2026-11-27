package app.trading.account.profile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 个人资料的 SQL，见 resources/mapper/account/ProfileMapper.xml。 */
@Mapper
interface ProfileMapper {

  ProfileRow find(@Param("userId") long userId);

  int update(@Param("userId") long userId, @Param("firstName") String firstName, @Param("middleName") String middleName, @Param("lastName") String lastName,
      @Param("birthDate") java.time.LocalDate birthDate, @Param("nationality") String nationality);

  /** 没有资料的客户（机器人开户）第一次保存 */
  int insert(@Param("userId") long userId, @Param("firstName") String firstName, @Param("middleName") String middleName, @Param("lastName") String lastName,
      @Param("birthDate") java.time.LocalDate birthDate, @Param("nationality") String nationality);
}
