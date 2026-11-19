ALTER TABLE places ADD COLUMN legacy_parent_place_id VARCHAR(64);
ALTER TABLE places ADD COLUMN legacy_parent_company_id VARCHAR(64);

CREATE TABLE legacy_place_hierarchy_repair_audit (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    place_id VARCHAR(64) NOT NULL,
    legacy_place_id VARCHAR(64) NOT NULL,
    before_parent_place_id VARCHAR(64),
    after_parent_place_id VARCHAR(64),
    source_parent_place_id VARCHAR(64),
    source_parent_company_id VARCHAR(64),
    source_sha256 VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK(outcome IN ('APPLIED','PRESERVED_MANUAL','CROSS_TENANT_CONTEXT','PARENT_UNAVAILABLE','SOURCE_MISMATCH')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_legacy_place_hierarchy_audit ON legacy_place_hierarchy_repair_audit(tenant_id,place_id,occurred_at);
-- Derived only from the read-only round6 org_all.csv export (SHA-256 below).
-- 162 importable child rows: 155 same-company links and 7 cross-company context-only links.
-- Eight further source rows belong to absent company 1 and are not imported or repaired.
-- Rows are (child legacy id, child company id, parent legacy id, parent company id).
CREATE TEMPORARY TABLE legacy_place_hierarchy_source (
    child_legacy_id VARCHAR(64) PRIMARY KEY,
    child_company_id VARCHAR(64) NOT NULL,
    parent_legacy_id VARCHAR(64) NOT NULL,
    parent_company_id VARCHAR(64) NOT NULL
);
INSERT INTO legacy_place_hierarchy_source VALUES
('324','294','668','294'),
('346','309','345','309'),
('347','309','345','309'),
('349','310','348','310'),
('350','310','348','310'),
('351','310','348','310'),
('352','310','348','310'),
('353','310','348','310'),
('356','314','355','314'),
('357','314','355','314'),
('358','314','355','314'),
('359','314','355','314'),
('369','294','323','294'),
('370','294','512','294'),
('371','294','512','294'),
('372','294','323','294'),
('373','294','512','294'),
('374','294','323','294'),
('378','294','669','294'),
('380','294','324','294'),
('381','294','325','294'),
('384','294','325','294'),
('386','295','329','295'),
('388','296','333','296'),
('389','296','553','296'),
('390','296','360','296'),
('391','296','555','296'),
('392','296','361','296'),
('393','296','361','296'),
('394','296','362','296'),
('395','296','557','296'),
('396','297','363','297'),
('397','297','363','297'),
('425','297','365','297'),
('426','297','366','297'),
('427','297','367','297'),
('428','297','367','297'),
('429','297','367','297'),
('430','297','367','297'),
('431','297','367','297'),
('432','297','367','297'),
('433','297','367','297'),
('434','297','368','297'),
('435','297','368','297'),
('440','313','439','313'),
('461','313','441','313'),
('462','313','441','313'),
('463','313','442','313'),
('464','313','443','313'),
('465','313','444','313'),
('466','313','445','313'),
('467','313','447','313'),
('468','313','448','313'),
('469','313','449','313'),
('470','313','450','313'),
('471','313','451','313'),
('472','313','452','313'),
('473','313','453','313'),
('474','313','454','313'),
('475','313','455','313'),
('476','313','456','313'),
('477','313','457','313'),
('478','313','458','313'),
('479','313','459','313'),
('480','313','460','313'),
('481','315','344','315'),
('482','315','344','315'),
('483','311','340','311'),
('484','311','340','311'),
('485','311','340','311'),
('486','311','341','311'),
('487','311','342','311'),
('488','311','343','311'),
('489','311','343','311'),
('490','316','334','316'),
('515','297','514','297'),
('516','297','514','297'),
('518','297','368','297'),
('519','297','368','297'),
('520','297','514','297'),
('521','297','520','297'),
('522','297','520','297'),
('523','297','520','297'),
('524','294','324','294'),
('525','294','512','294'),
('526','294','323','294'),
('527','294','323','294'),
('528','294','323','294'),
('529','294','512','294'),
('530','294','512','294'),
('531','327','362','296'),
('532','294','669','294'),
('538','327','537','296'),
('554','327','553','296'),
('556','327','555','296'),
('558','327','557','296'),
('559','327','333','296'),
('560','327','360','296'),
('561','327','655','327'),
('562','327','655','327'),
('570','297','514','297'),
('571','297','514','297'),
('572','311','511','311'),
('573','311','511','311'),
('574','297','368','297'),
('576','319','550','319'),
('577','319','550','319'),
('578','319','550','319'),
('579','319','565','319'),
('580','319','565','319'),
('581','319','568','319'),
('582','319','568','319'),
('583','319','568','319'),
('584','319','569','319'),
('585','319','569','319'),
('597','322','592','322'),
('598','322','592','322'),
('599','322','592','322'),
('600','322','592','322'),
('601','323','593','323'),
('602','323','593','323'),
('603','323','593','323'),
('604','323','593','323'),
('605','323','593','323'),
('610','325','609','325'),
('611','325','609','325'),
('612','325','609','325'),
('629','328','628','328'),
('630','328','628','328'),
('631','328','628','328'),
('633','328','632','328'),
('634','328','632','328'),
('635','328','632','328'),
('637','328','636','328'),
('638','328','636','328'),
('639','329','626','329'),
('640','329','626','329'),
('641','329','639','329'),
('642','329','639','329'),
('643','329','640','329'),
('644','329','640','329'),
('645','329','639','329'),
('646','329','639','329'),
('647','294','512','294'),
('648','294','512','294'),
('649','294','512','294'),
('650','294','669','294'),
('651','294','324','294'),
('652','294','668','294'),
('653','294','325','294'),
('654','294','325','294'),
('656','319','563','319'),
('657','319','563','319'),
('658','319','563','319'),
('660','314','659','314'),
('661','314','659','314'),
('662','314','659','314'),
('663','319','566','319'),
('669','294','668','294'),
('670','297','368','297'),
('676','319','569','319'),
('677','319','569','319');

INSERT INTO legacy_place_hierarchy_repair_audit
    (id,run_id,tenant_id,place_id,legacy_place_id,before_parent_place_id,after_parent_place_id,
     source_parent_place_id,source_parent_company_id,source_sha256,outcome,occurred_at)
SELECT 'V53-HIER-'||s.child_legacy_id,
       'a232a8b49ac920910a44a74a11203f189406a9e60e94d69c52c187bcef137a40',
       'LEGACY-'||s.child_company_id,child.id,s.child_legacy_id,child.parent_place_id,
       CASE WHEN s.child_company_id=s.parent_company_id AND parent.id IS NOT NULL
                  AND child.parent_place_id IS NULL AND child.revision=0
            THEN 'LEGACY-PLACE-'||s.parent_legacy_id ELSE child.parent_place_id END,
       s.parent_legacy_id,s.parent_company_id,
       'a232a8b49ac920910a44a74a11203f189406a9e60e94d69c52c187bcef137a40',
       CASE WHEN s.child_company_id<>s.parent_company_id THEN 'CROSS_TENANT_CONTEXT'
            WHEN parent.id IS NULL THEN 'PARENT_UNAVAILABLE'
            WHEN child.parent_place_id IS NULL AND child.revision=0 THEN 'APPLIED'
            WHEN child.parent_place_id='LEGACY-PLACE-'||s.parent_legacy_id THEN 'APPLIED'
            ELSE 'PRESERVED_MANUAL' END,
       CURRENT_TIMESTAMP
FROM legacy_place_hierarchy_source s
JOIN places child ON child.id='LEGACY-PLACE-'||s.child_legacy_id
    AND child.tenant_id='LEGACY-'||s.child_company_id AND child.legacy_place_id=s.child_legacy_id
LEFT JOIN places parent ON parent.id='LEGACY-PLACE-'||s.parent_legacy_id
    AND parent.tenant_id='LEGACY-'||s.parent_company_id AND parent.legacy_place_id=s.parent_legacy_id;

UPDATE places child
SET legacy_parent_place_id=s.parent_legacy_id,legacy_parent_company_id=s.parent_company_id
FROM legacy_place_hierarchy_source s
WHERE child.id='LEGACY-PLACE-'||s.child_legacy_id
    AND child.tenant_id='LEGACY-'||s.child_company_id AND child.legacy_place_id=s.child_legacy_id;

UPDATE places child
SET parent_place_id=(
        SELECT a.after_parent_place_id FROM legacy_place_hierarchy_repair_audit a
        WHERE a.id='V53-HIER-'||child.legacy_place_id
          AND a.place_id=child.id AND a.tenant_id=child.tenant_id
    ),revision=revision+1
WHERE child.parent_place_id IS NULL AND child.revision=0
  AND EXISTS (
      SELECT 1 FROM legacy_place_hierarchy_repair_audit a
      WHERE a.id='V53-HIER-'||child.legacy_place_id
        AND a.place_id=child.id AND a.tenant_id=child.tenant_id
        AND a.outcome='APPLIED' AND a.before_parent_place_id IS NULL
        AND a.after_parent_place_id IS NOT NULL
  );

DROP TABLE legacy_place_hierarchy_source;
