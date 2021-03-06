create table src_orc_merge_test(key int, value string) stored as orc;

insert overwrite table src_orc_merge_test select * from src;
insert into table src_orc_merge_test select * from src;
insert into table src_orc_merge_test select * from src;

show table extended like `src_orc_merge_test`;

select count(1) from src_orc_merge_test;
select sum(hash(key)), sum(hash(value)) from src_orc_merge_test;

alter table src_orc_merge_test concatenate;

show table extended like `src_orc_merge_test`;

select count(1) from src_orc_merge_test;
select sum(hash(key)), sum(hash(value)) from src_orc_merge_test;


create table src_orc_merge_test_part(key int, value string) partitioned by (ds string) stored as orc;

alter table src_orc_merge_test_part add partition (ds='2011');

insert overwrite table src_orc_merge_test_part partition (ds='2011') select * from src;
insert into table src_orc_merge_test_part partition (ds='2011') select * from src;
insert into table src_orc_merge_test_part partition (ds='2011') select * from src;

show table extended like `src_orc_merge_test_part` partition (ds='2011');

select count(1) from src_orc_merge_test_part;
select sum(hash(key)), sum(hash(value)) from src_orc_merge_test_part;

alter table src_orc_merge_test_part partition (ds='2011') concatenate;

show table extended like `src_orc_merge_test_part` partition (ds='2011');

select count(1) from src_orc_merge_test_part;
select sum(hash(key)), sum(hash(value)) from src_orc_merge_test_part;

drop table src_orc_merge_test;
drop table src_orc_merge_test_part;
