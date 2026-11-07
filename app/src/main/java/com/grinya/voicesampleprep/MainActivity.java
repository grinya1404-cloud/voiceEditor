package com.grinya.voicesampleprep;

// +инсерт 
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.grinya.voicesampleprep.model.Bank;
import com.grinya.voicesampleprep.model.BankManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

public class MainActivity extends AppCompatActivity {

  private BankManager bankManager;
  private RecyclerView rv;
  private TextView txtEmpty;
  private BankAdapter adapter;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    bankManager = new BankManager(this);
    rv = findViewById(R.id.rvBanks);
    txtEmpty = findViewById(R.id.txtEmpty);
    adapter = new BankAdapter();
    rv.setLayoutManager(new LinearLayoutManager(this));
    rv.setAdapter(adapter);

    findViewById(R.id.fabAdd).setOnClickListener(v -> showCreateBankDialog());

    findViewById(R.id.btnSettings).setOnClickListener(v -> {
      startActivity(new android.content.Intent(this, SettingsActivity.class));
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    reload();
  }

  private void reload() {
    List<Bank> banks = bankManager.listBanks();
    adapter.setData(banks);
    txtEmpty.setVisibility(banks.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private void showCreateBankDialog() {
    EditText input = new EditText(this);
    input.setHint("Имя банка");
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    new AlertDialog.Builder(this).setTitle("Новый банк").setView(input)
        .setPositiveButton("Создать", (d, w) -> {
          String name = input.getText().toString().trim();
          if (name.isEmpty())
            name = "bank";
          Bank b = bankManager.createBank(name);
          reload();
          openBank(b);
        }).setNegativeButton("Отмена", null).show();
  }

  private void openBank(Bank b) {
    BankActivity.currentBank = b;
    startActivity(new Intent(this, BankActivity.class));
  }

  // ---------- adapter ----------

  private class BankAdapter extends RecyclerView.Adapter<BankAdapter.VH> {
    private List<Bank> data = new java.util.ArrayList<>();

    void setData(List<Bank> d) {
      data = d;
      notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bank, parent, false);
      return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
      Bank b = data.get(pos);
      h.name.setText(b.name);
      h.info.setText(b.clips.size() + " клипов");
      h.itemView.setOnClickListener(v -> openBank(b));
      h.itemView.setOnLongClickListener(v -> {
        new AlertDialog.Builder(MainActivity.this).setTitle("Удалить банк?").setMessage(b.name)
            .setPositiveButton("Удалить", (d, w) -> {
              bankManager.deleteBank(b);
              reload();
            }).setNegativeButton("Отмена", null).show();
        return true;
      });
    }

    @Override
    public int getItemCount() {
      return data.size();
    }

    class VH extends RecyclerView.ViewHolder {
      TextView name, info;

      VH(View v) {
        super(v);
        name = v.findViewById(R.id.txtBankName);
        info = v.findViewById(R.id.txtBankInfo);
      }
    }
  }
}