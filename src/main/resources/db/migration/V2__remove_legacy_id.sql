-- O id inteiro temporário (ponte com o backend legado em MySQL) não tem mais uso: o legado foi desligado e todos os
-- serviços identificam o usuário só pelo UUID da conta.
alter table profile drop column legacy_id;
drop sequence legacy_id_seq;
