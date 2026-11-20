package com.github.moyingshi.osc.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.github.moyingshi.osc.tv.bean.Want;

import java.util.List;

@Dao
public abstract class WantDao extends BaseDao<Want> {

    @Query("SELECT * FROM Want ORDER BY createTime DESC")
    public abstract List<Want> findAll();

    @Query("SELECT * FROM Want WHERE `key` = :key")
    public abstract Want find(String key);

    @Query("SELECT EXISTS(SELECT 1 FROM Want WHERE `key` = :key)")
    public abstract boolean exist(String key);

    @Query("DELETE FROM Want WHERE `key` = :key")
    public abstract void delete(String key);

    @Query("DELETE FROM Want")
    public abstract void deleteAll();
}
